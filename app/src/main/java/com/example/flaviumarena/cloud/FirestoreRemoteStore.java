package com.example.flaviumarena.cloud;

import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.sync.Change;
import com.example.flaviumarena.sync.PublicView;
import com.example.flaviumarena.sync.RemoteStore;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The shared server, kept in Cloud Firestore. This is the real implementation of the
 * RemoteStore interface that the synchronisation engine talks to.
 *
 * Where things are stored (all under the document festivals/main):
 *
 *   festivals/main        who uploaded the festival, whether it is complete, and "rev",
 *                         the revision counter shared by every row
 *   .../pub/{table:key}   rows everyone may read (and the public copies of split tables)
 *   .../priv/{table:key}  rows only organisers may read
 *
 * A row document holds: t (table), k (key), v (the columns), rev, del (removed), br
 * (branch), sp (sport), dev (the device that wrote it) and by (who wrote it). The id of a
 * row never changes, so the links between rows are the same on every device.
 *
 * A change is written in a transaction that reads the row and the counter first. If the
 * row's revision is no longer the one the device last saw, nothing is written and the
 * caller gets the server's copy back. Otherwise the counter goes up by one and the row
 * is stored with that number. The security rules check the same arithmetic, so a row can
 * never be stored with an old or invented revision.
 *
 * Every method here waits for the network and must not be called on the main thread.
 */
public class FirestoreRemoteStore implements RemoteStore {

    private static final long TIMEOUT_SECONDS = 25;

    /** What the signed-in person may read of the private rows. Null: only the public rows. */
    public static class Scope {
        public String uid;
        public String role;
        public String branchId;
        public List<String> sportIds = new ArrayList<>();
    }

    /** Receives rows that changed on the server while the app is open. */
    public interface ChangeSink {
        void onChanges(List<Change> changes);

        void onError(String message);
    }

    private final FirebaseFirestore db;
    private final DocumentReference meta;
    private final CollectionReference pub;
    private final CollectionReference priv;
    private final long deviceId;
    private volatile Scope scope;

    public FirestoreRemoteStore(FirebaseFirestore db, long deviceId) {
        this.db = db;
        this.deviceId = deviceId;
        this.meta = db.collection("festivals").document("main");
        this.pub = meta.collection("pub");
        this.priv = meta.collection("priv");
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }

    DocumentReference meta() {
        return meta;
    }

    // =====================================================================
    // Sending one change
    // =====================================================================

    /** What a transaction found: the new revision, or the server's copy that is in the way. */
    private static class Outcome {
        long rev;
        Change conflict;
        boolean noFestival;
    }

    @Override
    public long push(final Change change, final long baseRev) {
        final String id = change.table + ":" + change.key;
        final boolean publicTable = !PublicView.isPrivate(change.table)
                && !PublicView.isSplit(change.table);
        final DocumentReference primary = (publicTable ? pub : priv).document(id);
        final String uid = scope == null ? "" : scope.uid;

        Outcome outcome = await(db.runTransaction(transaction -> {
            Outcome result = new Outcome();
            DocumentSnapshot festival = transaction.get(meta);
            DocumentSnapshot current = transaction.get(primary);
            if (!festival.exists()) {
                result.noFestival = true;
                return result;
            }
            long currentRev = current.exists() ? longOf(current, "rev") : 0;
            if (currentRev != baseRev) {
                result.conflict = current.exists() ? toChange(current, true)
                        : new Change(change.table, change.key, true, null);
                return result;
            }
            long rev = longOf(festival, "rev") + 1;
            // A removal keeps the branch and sport the row had, so the rules can still
            // tell whose row it was.
            if (change.deleted && current.exists()) {
                change.branch = stringOf(current, "br");
                change.sport = stringOf(current, "sp");
            }
            change.rev = rev;
            // The counter names the row it moves for; the rules refuse a step of the
            // counter that does not come with that row.
            transaction.update(meta, "rev", rev, "last", id, "lastPriv", !publicTable);
            transaction.set(primary, toDocument(change, uid));
            if (PublicView.isSplit(change.table)) {
                transaction.set(pub.document(id), toDocument(PublicView.of(change), uid));
            }
            result.rev = rev;
            return result;
        }));
        if (outcome.noFestival) {
            throw new Refused("There is no shared festival on the server yet.");
        }
        if (outcome.conflict != null) {
            throw new Conflict(outcome.conflict);
        }
        return outcome.rev;
    }

    private Map<String, Object> toDocument(Change change, String uid) {
        Map<String, Object> document = new HashMap<>();
        document.put("t", change.table);
        document.put("k", change.key);
        document.put("rev", change.rev);
        document.put("del", change.deleted);
        document.put("v", new HashMap<>(change.values));
        document.put("br", change.branch == null ? "" : change.branch);
        document.put("sp", change.sport == null ? "" : change.sport);
        document.put("dev", deviceId);
        document.put("by", uid);
        return document;
    }

    @SuppressWarnings("unchecked")
    private static Change toChange(DocumentSnapshot document, boolean complete) {
        String table = stringOf(document, "t");
        Map<String, Object> values = new LinkedHashMap<>();
        Object stored = document.get("v");
        if (stored instanceof Map) {
            values.putAll((Map<String, Object>) stored);
        }
        boolean deleted = Boolean.TRUE.equals(document.getBoolean("del"));
        Change change = new Change(table, stringOf(document, "k"), deleted,
                deleted ? null : values);
        change.rev = longOf(document, "rev");
        change.branch = stringOf(document, "br");
        change.sport = stringOf(document, "sp");
        change.complete = complete || !PublicView.isSplit(table);
        return change;
    }

    private static long longOf(DocumentSnapshot document, String field) {
        Long value = document.getLong(field);
        return value == null ? 0 : value;
    }

    private static String stringOf(DocumentSnapshot document, String field) {
        String value = document.getString(field);
        return value == null ? "" : value;
    }

    // =====================================================================
    // Reading
    // =====================================================================

    /**
     * The questions this device may ask the server. Everyone reads the public rows; what
     * is read of the private rows depends on the role, and the security rules refuse
     * anything wider than this.
     */
    private List<Query> queries(long since) {
        List<Query> list = new ArrayList<>();
        list.add(pub.whereGreaterThan("rev", since).orderBy("rev"));
        Scope s = scope;
        if (s == null || s.role == null) {
            return list;
        }
        if (Role.FACULTY.equals(s.role)) {
            list.add(priv.whereGreaterThan("rev", since).orderBy("rev"));
        } else if (Role.CORE.equals(s.role)) {
            list.add(privateTables(since, "registrations", "users", "score_audit", "event_audit",
                    "coach_sports"));
        } else if (Role.ASOO_CORE.equals(s.role)) {
            list.add(privateTables(since, "registrations", "score_audit", "event_audit",
                    "coach_sports"));
        } else if (Role.COACH.equals(s.role)) {
            // A Coach sees who coaches what, and the registrations and histories of the
            // sports assigned to them: one question per sport, because the server only
            // answers a question it can see is limited to an assigned sport.
            list.add(privateTables(since, "coach_sports"));
            for (String sportId : s.sportIds) {
                list.add(priv.whereIn("t", Arrays.asList("registrations", "score_audit",
                                "event_audit")).whereEqualTo("sp", sportId)
                        .whereGreaterThan("rev", since).orderBy("rev"));
            }
        } else if (Role.CAPTAIN.equals(s.role) && s.branchId != null) {
            // A Branch CR sees the complete registrations of their own branch only.
            list.add(priv.whereEqualTo("t", "registrations").whereEqualTo("br", s.branchId)
                    .whereGreaterThan("rev", since).orderBy("rev"));
        }
        return list;
    }

    private Query privateTables(long since, String... tables) {
        return priv.whereIn("t", Arrays.asList(tables)).whereGreaterThan("rev", since)
                .orderBy("rev");
    }

    @Override
    public List<Change> changesSince(long revision) {
        List<Change> changes = new ArrayList<>();
        List<Query> queries = queries(revision);
        for (int i = 0; i < queries.size(); i++) {
            QuerySnapshot result = await(queries.get(i).get(Source.SERVER));
            // The first question is always the public one; the others are private rows.
            boolean fromPrivate = i > 0;
            for (DocumentSnapshot document : result.getDocuments()) {
                changes.add(toChange(document, fromPrivate));
            }
        }
        Collections.sort(changes, new Comparator<Change>() {
            @Override
            public int compare(Change x, Change y) {
                return Long.compare(x.rev, y.rev);
            }
        });
        return changes;
    }

    @Override
    public Change get(String table, String key) {
        boolean publicTable = !PublicView.isPrivate(table) && !PublicView.isSplit(table);
        DocumentSnapshot document = await((publicTable ? pub : priv)
                .document(table + ":" + key).get(Source.SERVER));
        return document.exists() ? toChange(document, true) : null;
    }

    /**
     * Starts listening for rows that change after the given revision. The returned
     * object stops all the listeners again; the caller must call remove() on it.
     *
     * Only what the server confirms is passed on. Firestore first replays what it has
     * kept on the phone from earlier sessions, which can be out of date (or belong to a
     * festival that has since been reset): that replay is ignored. The local database is
     * this app's offline copy, not Firestore's cache.
     */
    public ListenerRegistration listen(long since, final ChangeSink sink) {
        final List<ListenerRegistration> registrations = new ArrayList<>();
        List<Query> queries = queries(since);
        for (int i = 0; i < queries.size(); i++) {
            final boolean fromPrivate = i > 0;
            // After an ignored replay, the next server answer is read whole: its list of
            // changes is relative to the replay, so it would leave out rows that were in
            // both.
            final boolean[] readWhole = {true};
            registrations.add(queries.get(i).addSnapshotListener((snapshot, error) -> {
                if (error != null) {
                    sink.onError(describe(error));
                    return;
                }
                if (snapshot == null) {
                    return;
                }
                if (snapshot.getMetadata().isFromCache()) {
                    readWhole[0] = true;
                    return;
                }
                List<Change> changes = new ArrayList<>();
                if (readWhole[0]) {
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        changes.add(toChange(document, fromPrivate));
                    }
                    readWhole[0] = false;
                } else {
                    for (DocumentChange change : snapshot.getDocumentChanges()) {
                        if (change.getType() != DocumentChange.Type.REMOVED) {
                            changes.add(toChange(change.getDocument(), fromPrivate));
                        }
                    }
                }
                if (!changes.isEmpty()) {
                    sink.onChanges(changes);
                }
            }));
        }
        return () -> {
            for (ListenerRegistration registration : registrations) {
                registration.remove();
            }
        };
    }

    // =====================================================================
    // The festival as a whole
    // =====================================================================

    @Override
    public Dataset dataset() {
        DocumentSnapshot festival = await(meta.get(Source.SERVER));
        if (!festival.exists()) {
            return new Dataset(0, false);
        }
        return new Dataset(longOf(festival, "owner"),
                Boolean.TRUE.equals(festival.getBoolean("ready")));
    }

    @Override
    public boolean claim(final long device) {
        final String uid = scope == null ? "" : scope.uid;
        return await(db.runTransaction(transaction -> {
            DocumentSnapshot festival = transaction.get(meta);
            if (!festival.exists()) {
                Map<String, Object> created = new HashMap<>();
                created.put("owner", device);
                created.put("ownerUid", uid);
                created.put("ready", false);
                created.put("rev", 0L);
                transaction.set(meta, created);
                return true;
            }
            return longOf(festival, "owner") == device
                    && !Boolean.TRUE.equals(festival.getBoolean("ready"));
        }));
    }

    @Override
    public void markReady(long device) {
        await(meta.update("ready", true));
    }

    // =====================================================================
    // Waiting for the network
    // =====================================================================

    /** Waits for a Firebase call and turns its failures into the three kinds the engine knows. */
    static <T> T await(Task<T> task) {
        try {
            return Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new Unavailable("The server did not answer in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("The request was interrupted.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FirebaseFirestoreException) {
                FirebaseFirestoreException.Code code =
                        ((FirebaseFirestoreException) cause).getCode();
                if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                        || code == FirebaseFirestoreException.Code.UNAUTHENTICATED) {
                    throw new Refused("The server does not allow this account to do that.");
                }
                throw new Unavailable(describe((FirebaseFirestoreException) cause));
            }
            throw new Unavailable(cause == null ? "The server could not be reached."
                    : String.valueOf(cause.getMessage()));
        }
    }

    private static String describe(FirebaseFirestoreException error) {
        switch (error.getCode()) {
            case UNAVAILABLE:
            case DEADLINE_EXCEEDED:
                return "No connection to the server.";
            case PERMISSION_DENIED:
                return "The server refused to show this data to this account.";
            case FAILED_PRECONDITION:
                // Usually a missing index: the message contains the link to create it.
                return "The server is not set up completely: " + error.getMessage();
            default:
                return "The server reported a problem: " + error.getMessage();
        }
    }
}
