package in.flavium.arena.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import in.flavium.arena.model.User;
import in.flavium.arena.util.DateUtil;
import static in.flavium.arena.data.Sql.*;

/**
 * Login sessions. An account has one live session at a time; this class opens, checks
 * and ends them. The rule is kept in two places on purpose: the code below, and a unique
 * index in the database that refuses a second live session whatever the code does.
 */
final class SessionStore {

    /** A session that has not been used for this long counts as ended. */
    static final long SESSION_IDLE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final DbHelper helper;

    SessionStore(DbHelper helper) {
        this.helper = helper;
    }

    /**
     * Opens a session for a user whose password has just been checked. Everything
     * happens in one transaction, and the database has a rule of its own (a unique
     * index) against two live sessions for one account, so two logins at the same
     * moment cannot both succeed.
     */
    String openSession(User user, String device, boolean takeOver) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            // A session that was abandoned (app removed, phone lost) ends by itself.
            endSessions(db, "user_id = ? AND last_seen_at < ?", SqliteArenaRepository.END_EXPIRED, user.id,
                    String.valueOf(now - SESSION_IDLE_MILLIS));

            String otherDevice = null;
            long lastSeen = 0;
            Cursor c = db.rawQuery("SELECT device, last_seen_at FROM sessions "
                    + "WHERE user_id = ? AND revoked_at IS NULL", new String[]{user.id});
            try {
                if (c.moveToFirst()) {
                    otherDevice = c.getString(0);
                    lastSeen = c.getLong(1);
                }
            } finally {
                c.close();
            }
            if (otherDevice != null) {
                if (!takeOver) {
                    throw new SessionActiveException("This account is already logged in"
                            + (otherDevice.isEmpty() ? "" : " on " + otherDevice)
                            + " (last active " + DateUtil.formatTimestamp(lastSeen) + "). "
                            + "Log out there first, or sign that session out and continue "
                            + "here.");
                }
                endSessions(db, "user_id = ?", SqliteArenaRepository.END_TAKEN_OVER, user.id);
            }

            String id = newSessionId();
            ContentValues cv = new ContentValues();
            cv.put("id", id);
            cv.put("user_id", user.id);
            cv.put("device", clean(device));
            cv.put("created_at", now);
            cv.put("last_seen_at", now);
            db.insertOrThrow("sessions", null, cv);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    /** Ends every live session that matches the condition. */
    static void endSessions(SQLiteDatabase db, String where, String reason,
                                    String... args) {
        ContentValues cv = new ContentValues();
        cv.put("revoked_at", System.currentTimeMillis());
        cv.put("revoked_reason", reason);
        db.update("sessions", cv, "revoked_at IS NULL AND " + where, args);
    }

    /** A long random value nobody can guess. */
    private static String newSessionId() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) {
            text.append(String.format(java.util.Locale.US, "%02x", b));
        }
        return text.toString();
    }

    /** The id of the user of a live session, or null. Old sessions are ended on the way. */
    String userOfLiveSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        String userId = null;
        long lastSeen = 0;
        Cursor c = helper.getReadableDatabase().rawQuery("SELECT user_id, last_seen_at FROM sessions "
                + "WHERE id = ? AND revoked_at IS NULL", new String[]{sessionId});
        try {
            if (c.moveToFirst()) {
                userId = c.getString(0);
                lastSeen = c.getLong(1);
            }
        } finally {
            c.close();
        }
        if (userId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (lastSeen < now - SESSION_IDLE_MILLIS) {
            endSessions(helper.getWritableDatabase(), "id = ?", SqliteArenaRepository.END_EXPIRED, sessionId);
            return null;
        }
        return userId;
    }
}
