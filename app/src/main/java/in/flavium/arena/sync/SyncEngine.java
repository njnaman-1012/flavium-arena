package in.flavium.arena.sync;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the local database and the shared server the same.
 *
 * The local database stays what every screen reads and writes, so the app works without
 * a connection. This class does two things around it:
 *
 *   pushPending()  sends the rows listed in sync_outbox to the server, oldest first
 *   applyRemote()  writes changes that came from the server into the local database
 *
 * The server is the authority. A local change is sent together with the revision of the
 * row this device last saw; if the server's copy has moved on, the change is refused,
 * the server's copy replaces the local one, and a notice says so. An old copy on a
 * device can therefore never overwrite newer data on the server.
 */
public class SyncEngine {

    /** Not connected: the device works on its own data and queues nothing. */
    public static final int LINK_NONE = 0;
    /** This device is uploading its data as the festival; not finished yet. */
    public static final int LINK_UPLOADING = 1;
    /** Connected: local changes are queued and sent, server changes are applied. */
    public static final int LINK_CONNECTED = 2;

    /** What connect() found. */
    public enum Connection {
        /** This device shares the online festival; changes travel both ways by themselves. */
        CONNECTED,
        /** Nothing is online yet: online sync has to be set up once, by Faculty. */
        NOT_SET_UP,
        /**
         * The online festival exists, but this device holds festival data of its own that
         * is not online. It is never replaced without the person deciding it.
         */
        LOCAL_DATA_IN_THE_WAY,
        /** This device is setting up online sync and has not finished. */
        SETTING_UP
    }

    /** Told after server changes were written locally, with the tables they touched. */
    public interface Listener {
        void onRemoteChanges(Set<String> tables);
    }

    /** A snapshot for a status line: is it connected, is anything waiting, did it work. */
    public static class Status {
        public int link;
        public int pending;
        /** Milliseconds; 0 when there has not been a complete exchange yet. */
        public long lastSync;
        /** Why the last attempt stopped, or "" when it completed. */
        public String lastError = "";
    }

    private final SQLiteOpenHelper helper;
    private final RemoteStore remote;
    private Listener listener;
    private String lastError = "";
    /** Things the person should be told: conflicts, refusals, rows that could not be applied. */
    private final List<String> notices = new ArrayList<>();
    /** See lastReceived(). Kept in memory only, for the screens that are open. */
    private final Map<String, Long> received = new ConcurrentHashMap<>();

    public SyncEngine(SQLiteOpenHelper helper, RemoteStore remote) {
        this.helper = helper;
        this.remote = remote;
    }

    // =====================================================================
    // Switching on
    // =====================================================================

    public boolean isEnabled() {
        return state(SyncSchema.STATE_CAPTURE) == 1;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Status status() {
        Status status = new Status();
        status.link = (int) state(SyncSchema.STATE_LINK);
        status.pending = pendingCount();
        status.lastSync = state(SyncSchema.STATE_LAST_SYNC);
        status.lastError = lastError;
        return status;
    }

    /**
     * True when this device holds festival data of its own: sports, registrations,
     * fixtures or accounts. A new installation has none (only sample branches).
     */
    public boolean hasLocalFestival() {
        SQLiteDatabase db = helper.getReadableDatabase();
        return DatabaseUtils.queryNumEntries(db, "sports") > 0
                || DatabaseUtils.queryNumEntries(db, "registrations") > 0
                || DatabaseUtils.queryNumEntries(db, "matches") > 0
                || DatabaseUtils.queryNumEntries(db, "users") > 0;
    }

    /**
     * Connects this device to the online festival, without anyone pressing anything.
     * This is how every device becomes part of the festival: there is no "main" device.
     *
     *   already connected          nothing to do
     *   nothing online yet         NOT_SET_UP: the device keeps working on its own data
     *   festival online, and this
     *   device has no data of its
     *   own (a new installation)   it takes the online festival and is connected
     *   festival online, and this
     *   device has data of its own LOCAL_DATA_IN_THE_WAY, unless replaceLocalData is
     *                              true (the person decided it): then that data is
     *                              replaced by the online festival
     *
     * beforeJoining runs just before local data is replaced (to keep a copy of it); if
     * it throws, nothing is replaced. Needs the network: RemoteStore.Unavailable when
     * the server cannot be reached, and the device stays as it was.
     */
    public Connection connect(boolean replaceLocalData, Runnable beforeJoining) {
        long link = state(SyncSchema.STATE_LINK);
        if (link == LINK_CONNECTED) {
            return Connection.CONNECTED;
        }
        if (link == LINK_UPLOADING) {
            return Connection.SETTING_UP;
        }
        if (!remote.dataset().ready) {
            return Connection.NOT_SET_UP;
        }
        if (hasLocalFestival() && !replaceLocalData) {
            return Connection.LOCAL_DATA_IN_THE_WAY;
        }
        if (beforeJoining != null) {
            beforeJoining.run();
        }
        joinFestival();
        return Connection.CONNECTED;
    }

    /**
     * FIRST DEVICE. Uploads this device's data as the shared festival. To be called only
     * after an administrator has confirmed that this device is the one to copy from.
     *
     * It is refused when the server already has a festival or another device is
     * uploading one, so two devices can never both become the source. It can be
     * interrupted at any point (no connection, app closed): calling it again continues
     * where it stopped and sends nothing twice. Other devices can join only after the
     * last row has arrived.
     */
    public void uploadAsFestival() {
        long link = state(SyncSchema.STATE_LINK);
        if (link == LINK_CONNECTED) {
            throw new SyncException("This device is already connected to the shared festival.");
        }
        long device = deviceId();
        RemoteStore.Dataset dataset = remote.dataset();
        if (dataset.ready || (dataset.owner != 0 && dataset.owner != device)
                || !remote.claim(device)) {
            throw new SyncException("The festival is already online, or is being put online "
                    + "from another device. This device connects to it by itself.");
        }
        if (link == LINK_NONE) {
            seedRemote();
            setState(helper.getWritableDatabase(), SyncSchema.STATE_LINK, LINK_UPLOADING);
        }
        pushPending();
        if (pendingCount() > 0) {
            throw new SyncException("Some records were not accepted, so the festival is not "
                    + "fully online yet. Check the messages shown and try again.");
        }
        remote.markReady(device);
        SQLiteDatabase db = helper.getWritableDatabase();
        setState(db, SyncSchema.STATE_LINK, LINK_CONNECTED);
        setState(db, SyncSchema.STATE_LAST_SYNC, System.currentTimeMillis());
    }

    /**
     * EVERY OTHER DEVICE. Replaces this device's shared tables with the festival on the
     * server. To be called only after the person has confirmed it and a copy of the
     * local database has been kept (see LocalBackup), because local festival data that
     * was never uploaded is not merged: it is replaced.
     *
     * It is refused until a festival has been uploaded completely. If it is interrupted
     * the device stays unconnected and joining again starts cleanly.
     */
    public void joinFestival() {
        if (state(SyncSchema.STATE_LINK) != LINK_NONE) {
            throw new SyncException("This device is already connected, or is the one "
                    + "uploading the festival.");
        }
        if (!remote.dataset().ready) {
            throw new SyncException("There is no complete festival on the server yet. The "
                    + "first device has to finish uploading before others can join.");
        }
        adoptRemote();
        SQLiteDatabase db = helper.getWritableDatabase();
        setState(db, SyncSchema.STATE_LINK, LINK_CONNECTED);
        setState(db, SyncSchema.STATE_LAST_SYNC, System.currentTimeMillis());
    }

    /**
     * One complete exchange: receive what is new, then send what is waiting. Receiving
     * first means a change that lost to a newer server version is dropped before it is
     * sent. Returns false, with the reason in status().lastError, when the server could
     * not be reached; everything stays queued.
     */
    public boolean sync() {
        if (state(SyncSchema.STATE_LINK) != LINK_CONNECTED) {
            return false;
        }
        try {
            pull();
            pushPending();
            pull();
            lastError = "";
            setState(helper.getWritableDatabase(), SyncSchema.STATE_LAST_SYNC,
                    System.currentTimeMillis());
            return true;
        } catch (RemoteStore.Unavailable e) {
            lastError = e.getMessage();
            return false;
        }
    }

    /** Switches change capture on and queues every local row for upload. */
    private void seedRemote() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (String table : SyncSchema.TABLES) {
                db.execSQL("INSERT OR REPLACE INTO sync_outbox (table_name, row_key, deleted) "
                        + "SELECT '" + table + "', " + keySql(table) + ", 0 FROM " + table);
            }
            setState(db, SyncSchema.STATE_CAPTURE, 1);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Empties the shared tables, loads the server's rows and switches change capture on. */
    private void adoptRemote() {
        List<Change> everything = remote.changesSince(0);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            setState(db, SyncSchema.STATE_CAPTURE, 0);
            List<String> reversed = new ArrayList<>(Arrays.asList(SyncSchema.TABLES));
            Collections.reverse(reversed);
            for (String table : reversed) {
                db.delete(table, null, null);
            }
            db.delete("sessions", null, null);
            db.delete("sync_outbox", null, null);
            db.delete("sync_rows", null, null);
            setState(db, SyncSchema.STATE_CURSOR, 0);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        applyRemote(everything);
        setState(helper.getWritableDatabase(), SyncSchema.STATE_CAPTURE, 1);
    }

    // =====================================================================
    // Local changes to the server
    // =====================================================================

    public int pendingCount() {
        return (int) DatabaseUtils.queryNumEntries(helper.getReadableDatabase(), "sync_outbox");
    }

    /**
     * Sends every queued change. Returns how many the server accepted. When the server
     * cannot be reached the method stops with RemoteStore.Unavailable and the remaining
     * changes stay queued for the next attempt; calling it again never sends a change
     * twice, because a line leaves the queue only when the server has confirmed it.
     */
    public int pushPending() {
        int sent = 0;
        for (long[] entry : queued()) {
            if (pushOne(entry[0])) {
                sent++;
            }
        }
        return sent;
    }

    /** Queue lines in sending order: additions and changes parents first, removals children first. */
    private List<long[]> queued() {
        final List<String> order = Arrays.asList(SyncSchema.TABLES);
        List<long[]> entries = new ArrayList<>();
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT seq, table_name, deleted FROM sync_outbox", null);
        try {
            while (c.moveToNext()) {
                int position = order.indexOf(c.getString(1));
                boolean deleted = c.getInt(2) == 1;
                entries.add(new long[]{c.getLong(0), deleted ? 1 : 0,
                        deleted ? -position : position});
            }
        } finally {
            c.close();
        }
        Collections.sort(entries, new Comparator<long[]>() {
            @Override
            public int compare(long[] x, long[] y) {
                if (x[1] != y[1]) {
                    return Long.compare(x[1], y[1]);   // changes before removals
                }
                if (x[2] != y[2]) {
                    return Long.compare(x[2], y[2]);
                }
                return Long.compare(x[0], y[0]);
            }
        });
        return entries;
    }

    private boolean pushOne(long seq) {
        SQLiteDatabase db = helper.getWritableDatabase();
        String table;
        String key;
        Cursor c = db.rawQuery("SELECT table_name, row_key FROM sync_outbox WHERE seq = ?",
                new String[]{String.valueOf(seq)});
        try {
            if (!c.moveToFirst()) {
                return false; // changed again meanwhile: a newer line replaces it
            }
            table = c.getString(0);
            key = c.getString(1);
        } finally {
            c.close();
        }
        // What is sent is the row as it is now, not as it was when the line was written.
        Change local = readLocal(table, key);
        long baseRev = revOf(db, table, key);
        if (local.deleted && baseRev == 0) {
            // Added and removed again before it was ever sent: the server has nothing
            // to be told. (If an earlier attempt did reach the server and only its
            // answer was lost, the server's copy comes back instead of being removed
            // unseen.)
            Change server = serverCopy(table, key);
            if (server == null || server.deleted) {
                db.delete("sync_outbox", "seq = ?", new String[]{String.valueOf(seq)});
                return false;
            }
            takeServerCopy(server, table, key);
            notices.add("Removing " + describe(table) + " did not go through: it had "
                    + "already been saved for everyone, so it is shown again. Remove it once "
                    + "more if it should go.");
            return false;
        }
        if (baseRev > 0 && !isComplete(db, table, key)) {
            // This device only ever received the public copy of the row (no phone
            // number, no email), so sending it would erase the rest on the server.
            takeServerCopy(serverCopy(table, key), table, key);
            notices.add("Your change to " + describe(table) + " could not be saved on this "
                    + "device. The latest version is now shown.");
            return false;
        }
        try {
            long rev = remote.push(local, baseRev);
            confirm(db, seq, table, key, rev);
            return true;
        } catch (RemoteStore.Conflict conflict) {
            if (local.sameContentAs(conflict.current)) {
                // The server already has exactly this: an earlier attempt got through
                // but its answer was lost. Nothing to resolve.
                confirm(db, seq, table, key, conflict.current.rev);
                return true;
            }
            dropQueuedDependents(table, key);
            takeServerCopy(conflict.current, table, key);
            notices.add("Your change to " + describe(table) + " was not saved because someone "
                    + "else changed it first. The latest version is now shown.");
            return false;
        } catch (RemoteStore.Refused refused) {
            dropQueuedDependents(table, key);
            takeServerCopy(serverCopy(table, key), table, key);
            notices.add("Your change to " + describe(table) + " was not saved: your "
                    + "account is not allowed to make it. The latest version is now shown.");
            return false;
        }
    }

    private void confirm(SQLiteDatabase db, long seq, String table, String key, long rev) {
        db.beginTransaction();
        try {
            setRev(db, table, key, rev, true);
            db.delete("sync_outbox", "seq = ?", new String[]{String.valueOf(seq)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Gives up the unsent rows that belong to a row whose change was not accepted. A
     * score that lost to someone else's must not leave its history line, its sets or
     * its events behind: they describe a state the match never officially had.
     */
    private void dropQueuedDependents(String table, String key) {
        SQLiteDatabase db = helper.getWritableDatabase();
        for (String[] dependent : SyncSchema.DEPENDENTS) {
            if (!dependent[1].equals(table)) {
                continue;
            }
            List<String> childKeys = new ArrayList<>();
            Cursor c = db.rawQuery("SELECT o.row_key FROM sync_outbox o JOIN " + dependent[0]
                    + " child ON child.id = o.row_key WHERE o.table_name = ? AND child."
                    + dependent[2] + " = ?", new String[]{dependent[0], key});
            try {
                while (c.moveToNext()) {
                    childKeys.add(c.getString(0));
                }
            } finally {
                c.close();
            }
            for (String childKey : childKeys) {
                dropQueuedDependents(dependent[0], childKey);
                takeServerCopy(serverCopy(dependent[0], childKey), dependent[0], childKey);
            }
        }
    }

    /**
     * The server's copy of a row, or null when it has none or will not show it to this
     * account. Either way the local change is given up.
     */
    private Change serverCopy(String table, String key) {
        try {
            return remote.get(table, key);
        } catch (RemoteStore.Refused e) {
            return null;
        }
    }

    /** Gives up a local change: the row becomes what the server has (or disappears). */
    private void takeServerCopy(Change server, String table, String key) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("sync_outbox", "table_name = ? AND row_key = ?", new String[]{table, key});
            write(db, server == null ? new Change(table, key, true, null) : server);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // =====================================================================
    // Server changes to the local database
    // =====================================================================

    /**
     * Replaces the shared tables with exactly what the current sign-in may see on the
     * server. Used when the person using the device changes: after a logout (the public
     * view, so the next person cannot find the previous account's private rows in the
     * database) and at the next login if that could not be done then. Only when nothing
     * is waiting to be sent, because waiting changes would be lost; returns false then,
     * or when the device is not connected. Needs the network.
     */
    public boolean replaceWithServerView() {
        if (state(SyncSchema.STATE_LINK) != LINK_CONNECTED || pendingCount() > 0) {
            return false;
        }
        adoptRemote();
        return true;
    }

    /**
     * Reads everything the server lets this device see and applies it. Used after
     * signing in, when rows that were only known as public copies may now be available
     * complete. Nothing is removed and nothing is sent.
     */
    public int reloadAll() {
        List<Change> changes = remote.changesSince(0);
        applyRemote(changes);
        return changes.size();
    }

    /**
     * The revision of the last version of a row that arrived FROM the server while the
     * app has been running (another device's change, or the server's copy replacing a
     * change of this device that was not accepted); 0 when none arrived. This device's
     * own changes do not count, so an edit screen can compare it to notice that
     * somebody else changed the record it shows.
     */
    public long lastReceived(String table, String key) {
        Long rev = received.get(table + ":" + key);
        return rev == null ? 0 : rev;
    }

    /** The highest server revision this device has applied; listeners start after it. */
    public long cursor() {
        return state(SyncSchema.STATE_CURSOR);
    }

    /** Asks the server for everything this device has not seen yet and applies it. */
    public int pull() {
        List<Change> changes = remote.changesSince(state(SyncSchema.STATE_CURSOR));
        applyRemote(changes);
        return changes.size();
    }

    /**
     * Writes changes from the server into the local database. This is also what a live
     * listener calls when the server announces a change.
     */
    public void applyRemote(List<Change> changes) {
        final List<String> order = Arrays.asList(SyncSchema.TABLES);
        List<Change> sorted = new ArrayList<>(changes);
        Collections.sort(sorted, new Comparator<Change>() {
            @Override
            public int compare(Change x, Change y) {
                if (x.deleted != y.deleted) {
                    return x.deleted ? 1 : -1;
                }
                int px = order.indexOf(x.table);
                int py = order.indexOf(y.table);
                if (px != py) {
                    return x.deleted ? py - px : px - py;
                }
                return Long.compare(x.rev, y.rev);
            }
        });

        SQLiteDatabase db = helper.getWritableDatabase();
        long cursor = state(SyncSchema.STATE_CURSOR);
        Set<String> touched = new HashSet<>();
        for (Change change : sorted) {
            if (!order.contains(change.table)) {
                continue;
            }
            cursor = Math.max(cursor, change.rev);
            long known = revOf(db, change.table, change.key);
            boolean knownComplete = known > 0 && isComplete(db, change.table, change.key);
            if (change.rev < known) {
                continue; // this device already has something newer
            }
            if (PublicView.isSplit(change.table)) {
                if (!change.complete && knownComplete) {
                    continue; // the public copy never replaces the complete row
                }
                if (change.rev == known && change.complete == knownComplete) {
                    continue;
                }
            } else if (change.rev == known) {
                continue; // already have this revision
            }
            // A local change to the same row that has not been sent loses: the server's
            // version is newer than what that change was based on. The rows written with
            // it go too.
            boolean unsent = DatabaseUtils.queryNumEntries(db, "sync_outbox",
                    "table_name = ? AND row_key = ?", new String[]{change.table, change.key}) > 0;
            if (unsent && change.complete && readLocal(change.table, change.key)
                    .sameContentAs(change)) {
                // The server already holds exactly what is waiting here (this device's own
                // change coming back, or the same edit made elsewhere): it is confirmed,
                // not overwritten, and nothing that belongs to it is dropped.
                db.beginTransaction();
                try {
                    setRev(db, change.table, change.key, change.rev, true);
                    db.delete("sync_outbox", "table_name = ? AND row_key = ?",
                            new String[]{change.table, change.key});
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                continue;
            }
            if (unsent) {
                dropQueuedDependents(change.table, change.key);
            }
            db.beginTransaction();
            try {
                if (unsent) {
                    db.delete("sync_outbox", "table_name = ? AND row_key = ?",
                            new String[]{change.table, change.key});
                    notices.add("Your unsent change to " + describe(change.table)
                            + " was replaced by a newer version from another device.");
                }
                write(db, change);
                touched.add(change.table);
                db.setTransactionSuccessful();
            } catch (SQLiteConstraintException e) {
                // For example a different local record with the same name. It is reported,
                // never forced: forcing it would silently destroy one of the two.
                Log.w("FlaviumSync", "Could not apply " + change.table + " " + change.key, e);
                notices.add("A change to " + describe(change.table) + " made on another "
                        + "device clashes with a record on this device and is not shown here.");
            } finally {
                db.endTransaction();
            }
        }
        setState(db, SyncSchema.STATE_CURSOR, cursor);
        if (listener != null && !touched.isEmpty()) {
            listener.onRemoteChanges(touched);
        }
    }

    /** Writes one server row (or removal) without the triggers queuing it again. */
    private void write(SQLiteDatabase db, Change change) {
        received.put(change.table + ":" + change.key, change.rev);
        long capture = state(SyncSchema.STATE_CAPTURE);
        setState(db, SyncSchema.STATE_CAPTURE, 0);
        try {
            String where = keyWhere(change.table);
            String[] keyValues = change.key.split("\\|", -1);
            if (change.deleted) {
                db.delete(change.table, where, keyValues);
            } else {
                ContentValues cv = new ContentValues();
                for (Map.Entry<String, Object> value : change.values.entrySet()) {
                    if (SyncSchema.isLocalOnly(change.table, value.getKey())) {
                        continue; // never accept a password hash from anywhere
                    }
                    put(cv, value.getKey(), value.getValue());
                }
                // An UPDATE keeps the row in place. (Replacing it would remove the rows
                // that depend on it, such as the sets of a match.)
                if (db.update(change.table, cv, where, keyValues) == 0) {
                    if ("users".equals(change.table)) {
                        // An account that arrives from the server has no password on this
                        // device: it can only sign in through the server.
                        cv.put("salt", "");
                        cv.put("password_hash", "");
                    }
                    db.insertOrThrow(change.table, null, cv);
                }
            }
            setRev(db, change.table, change.key, change.rev, change.complete);
        } finally {
            setState(db, SyncSchema.STATE_CAPTURE, capture);
        }
    }

    // =====================================================================
    // Notices
    // =====================================================================

    public boolean hasNotices() {
        return !notices.isEmpty();
    }

    /** What happened that the person should know about. Asking clears the list. */
    public List<String> takeNotices() {
        List<String> copy = new ArrayList<>(notices);
        notices.clear();
        return copy;
    }

    /** What a row is, in the words of the app, for the messages people see. */
    private static String describe(String table) {
        switch (table) {
            case "branches":
                return "a branch";
            case "users":
                return "an account";
            case "sports":
                return "a sport";
            case "coach_sports":
                return "a coach's sports";
            case "categories":
                return "a category";
            case "registrations":
                return "a registration";
            case "matches":
                return "a match";
            case "match_periods":
                return "a set or period of a match";
            case "match_events":
                return "a match event";
            case "score_audit":
                return "a score history entry";
            case "event_rounds":
                return "a heat or final";
            case "event_entries":
                return "an entrant's result";
            case "event_attempts":
                return "an attempt";
            case "event_audit":
                return "an event history entry";
            case "settings":
                return "a setting";
            case "admin_audit":
                return "the administrative log";
            default:
                return "a record";
        }
    }

    // =====================================================================
    // Reading and bookkeeping
    // =====================================================================

    /** The local row as a change, without the columns that never leave the device. */
    private Change readLocal(String table, String key) {
        Cursor c = helper.getReadableDatabase().rawQuery("SELECT * FROM " + table + " WHERE "
                + keyWhere(table), key.split("\\|", -1));
        try {
            if (!c.moveToFirst()) {
                return new Change(table, key, true, null);
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < c.getColumnCount(); i++) {
                String column = c.getColumnName(i);
                if (SyncSchema.isLocalOnly(table, column)) {
                    continue;
                }
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL:
                        values.put(column, null);
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        values.put(column, c.getLong(i));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        values.put(column, c.getDouble(i));
                        break;
                    default:
                        values.put(column, c.getString(i));
                }
            }
            Change change = new Change(table, key, false, values);
            Object branch = values.get("branch_id");
            if (branch != null && ("registrations".equals(table) || "users".equals(table))) {
                change.branch = String.valueOf(branch);
            }
            String sportSql = SyncSchema.sportSql(table);
            if (sportSql != null) {
                Cursor s = helper.getReadableDatabase().rawQuery(sportSql, new String[]{key});
                try {
                    if (s.moveToFirst() && !s.isNull(0)) {
                        change.sport = s.getString(0);
                    }
                } finally {
                    s.close();
                }
            }
            return change;
        } finally {
            c.close();
        }
    }

    private static void put(ContentValues cv, String column, Object value) {
        if (value == null) {
            cv.putNull(column);
        } else if (value instanceof Long) {
            cv.put(column, (Long) value);
        } else if (value instanceof Integer) {
            cv.put(column, ((Integer) value).longValue());
        } else if (value instanceof Double) {
            cv.put(column, (Double) value);
        } else {
            cv.put(column, String.valueOf(value));
        }
    }

    private static String keyWhere(String table) {
        StringBuilder where = new StringBuilder();
        for (String column : SyncSchema.keyColumns(table)) {
            where.append(where.length() > 0 ? " AND " : "").append(column).append(" = ?");
        }
        return where.toString();
    }

    private static String keySql(String table) {
        StringBuilder sql = new StringBuilder();
        for (String column : SyncSchema.keyColumns(table)) {
            sql.append(sql.length() > 0 ? " || '|' || " : "").append(column);
        }
        return sql.toString();
    }

    private static long revOf(SQLiteDatabase db, String table, String key) {
        Cursor c = db.rawQuery("SELECT rev FROM sync_rows WHERE table_name = ? AND row_key = ?",
                new String[]{table, key});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    private static boolean isComplete(SQLiteDatabase db, String table, String key) {
        Cursor c = db.rawQuery("SELECT complete FROM sync_rows WHERE table_name = ? "
                + "AND row_key = ?", new String[]{table, key});
        try {
            return !c.moveToFirst() || c.getInt(0) == 1;
        } finally {
            c.close();
        }
    }

    private static void setRev(SQLiteDatabase db, String table, String key, long rev,
                               boolean complete) {
        ContentValues cv = new ContentValues();
        cv.put("table_name", table);
        cv.put("row_key", key);
        cv.put("rev", rev);
        cv.put("complete", complete ? 1 : 0);
        db.insertWithOnConflict("sync_rows", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** A number kept between runs of the app; 0 when it has never been set. */
    private long state(String name) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT value FROM sync_state WHERE name = ?", new String[]{name});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    private static void setState(SQLiteDatabase db, String name, long value) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("value", value);
        db.insertWithOnConflict("sync_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** The number that identifies this installation; chosen at random the first time. */
    public long deviceId() {
        long id = state(SyncSchema.STATE_DEVICE);
        if (id == 0) {
            id = (new java.security.SecureRandom().nextLong() >>> 2) + 1;
            setState(helper.getWritableDatabase(), SyncSchema.STATE_DEVICE, id);
        }
        return id;
    }
}
