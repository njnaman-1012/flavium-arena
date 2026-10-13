package com.example.flaviumarena.cloud;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.flaviumarena.R;
import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.PermissionDeniedException;
import com.example.flaviumarena.data.RepositoryProvider;
import com.example.flaviumarena.data.SessionActiveException;
import com.example.flaviumarena.data.ValidationException;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.sync.Change;
import com.example.flaviumarena.sync.LocalBackup;
import com.example.flaviumarena.sync.RemoteStore;
import com.example.flaviumarena.sync.SyncEngine;
import com.example.flaviumarena.sync.SyncException;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Source;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The app's connection to the shared festival on Firebase. One object for the whole app.
 *
 * It owns the Firebase sign-in, the Firestore adapter, the synchronisation engine and
 * the live listeners, and tells the open screens when data or status changed. Screens
 * never talk to Firebase themselves.
 *
 * Without app/google-services.json the app is "not configured": every method here does
 * nothing and the app works on its local database as before.
 *
 * Methods marked "waits" use the network and must be called from a background thread
 * (BaseActivity.runBusy does that).
 */
public final class Cloud {

    private static final String TAG = "FlaviumCloud";
    private static final long TIMEOUT_SECONDS = 25;
    private static Cloud instance;

    /** Told on the main thread when shared data or the connection status changed. */
    public interface Observer {
        void onCloudChanged();
    }

    private final Context app;
    private final ArenaRepository repo;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** One thread, so sending and receiving never run at the same time. */
    private final Executor worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArraySet<Observer> observers = new CopyOnWriteArraySet<>();
    private final List<String> notices = new ArrayList<>();

    private FirebaseAuth auth;
    private FirebaseFirestore firestore;
    private FirestoreRemoteStore store;
    private SyncEngine engine;
    private ListenerRegistration rowListener;
    private ListenerRegistration sessionListener;
    private boolean syncQueued;
    /** Set when the account was signed in on another device; the next screen logs out. */
    private volatile boolean sessionTakenOver;

    public static synchronized Cloud get(Context context) {
        if (instance == null) {
            instance = new Cloud(context.getApplicationContext());
        }
        return instance;
    }

    private Cloud(Context context) {
        app = context;
        repo = RepositoryProvider.get(context);
        if (FirebaseApp.getApps(context).isEmpty()) {
            return; // no google-services.json: local only
        }
        auth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();
        // Debug builds can be pointed at the Firebase emulators on the development
        // computer (res/values/config.xml), so nothing has to touch the real project.
        String emulator = context.getString(R.string.firebase_emulator_host);
        boolean debuggable = (context.getApplicationInfo().flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable && !emulator.isEmpty()) {
            firestore.useEmulator(emulator, 8080);
            auth.useEmulator(emulator, 9099);
        }
        SyncEngine probe = new SyncEngine(RepositoryProvider.database(context), null);
        store = new FirestoreRemoteStore(firestore, probe.deviceId());
        engine = new SyncEngine(RepositoryProvider.database(context), store);
        engine.setListener(tables -> notifyObservers());
    }

    // =====================================================================
    // State
    // =====================================================================

    /** True when the app was built with a Firebase configuration. */
    public boolean isConfigured() {
        return engine != null;
    }

    /** True when this device shares the festival (it uploaded it or joined it). */
    public boolean isConnected() {
        return isConfigured() && engine.status().link == SyncEngine.LINK_CONNECTED;
    }

    public SyncEngine.Status status() {
        return isConfigured() ? engine.status() : new SyncEngine.Status();
    }

    public boolean hasLocalFestival() {
        return isConfigured() && engine.hasLocalFestival();
    }

    /** Conflicts, refusals and errors the person should see. Asking clears the list. */
    public List<String> takeNotices() {
        List<String> all = new ArrayList<>();
        if (isConfigured()) {
            all.addAll(engine.takeNotices());
        }
        synchronized (notices) {
            all.addAll(notices);
            notices.clear();
        }
        return all;
    }

    /** True once: the account was signed in elsewhere and this device must log out. */
    public boolean takeSessionTakenOver() {
        boolean taken = sessionTakenOver;
        sessionTakenOver = false;
        return taken;
    }

    public void addObserver(Observer observer) {
        observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    private void notifyObservers() {
        main.post(() -> {
            for (Observer observer : observers) {
                observer.onCloudChanged();
            }
        });
    }

    private void notice(String text) {
        synchronized (notices) {
            if (!notices.contains(text)) {
                notices.add(text);
            }
        }
    }

    // =====================================================================
    // Keeping up to date while the app is open
    // =====================================================================

    /** A screen came to the front: listen for changes and exchange what is waiting. */
    public void onForeground() {
        if (!isConnected()) {
            return;
        }
        worker.execute(() -> {
            applyScope(null);
            startListening();
        });
        requestSync();
    }

    /** The last screen went away: stop listening (the system may stop the app anyway). */
    public void onBackground() {
        if (!isConfigured() || !observers.isEmpty()) {
            return;
        }
        worker.execute(this::stopListening);
    }

    /**
     * Something was saved locally: send it soon. Several saves in a row cause one
     * exchange, and a failed exchange is retried the next time anything asks.
     */
    public void requestSync() {
        if (!isConnected()) {
            return;
        }
        synchronized (this) {
            if (syncQueued) {
                return;
            }
            syncQueued = true;
        }
        worker.execute(() -> {
            synchronized (Cloud.this) {
                syncQueued = false;
            }
            try {
                engine.sync();
            } catch (RuntimeException e) {
                Log.w(TAG, "Synchronisation failed", e);
                notice("Synchronisation stopped: " + e.getMessage());
            }
            // Screens are told only when there is something to say: a save that went
            // through quietly must not redraw the screen the person is typing on.
            boolean something;
            synchronized (notices) {
                something = !notices.isEmpty() || engine.hasNotices();
            }
            if (something) {
                notifyObservers();
            }
        });
    }

    /** Waits. One exchange right now, for the "Synchronise now" button. */
    public boolean syncNow() {
        boolean ok = engine.sync();
        notifyObservers();
        return ok;
    }

    private void startListening() {
        stopListening();
        rowListener = store.listen(engine.cursor(), new FirestoreRemoteStore.ChangeSink() {
            @Override
            public void onChanges(final List<Change> changes) {
                // The listener reports on the main thread; the database work must not run there.
                worker.execute(() -> {
                    try {
                        engine.applyRemote(changes);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Applying server changes failed", e);
                    }
                });
            }

            @Override
            public void onError(String message) {
                notice(message);
                notifyObservers();
            }
        });
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) {
            final long device = engine.deviceId();
            sessionListener = sessionDoc(user.getUid()).addSnapshotListener((snapshot, error) -> {
                if (snapshot != null && snapshot.exists() && snapshot.getLong("dev") != null
                        && snapshot.getLong("dev") != device) {
                    // The account signed in on another device.
                    sessionTakenOver = true;
                    notifyObservers();
                }
            });
        }
    }

    private void stopListening() {
        if (rowListener != null) {
            rowListener.remove();
            rowListener = null;
        }
        if (sessionListener != null) {
            sessionListener.remove();
            sessionListener = null;
        }
    }

    // =====================================================================
    // Who is signed in, for the server
    // =====================================================================

    private DocumentReference sessionDoc(String uid) {
        return store.meta().collection("sessions").document(uid);
    }

    private DocumentReference linkDoc(String uid) {
        return store.meta().collection("links").document(uid);
    }

    /**
     * Tells the adapter what this device may read: the role and branch of the account
     * that is signed in to Firebase, taken from the local copy of that account (or from
     * the given user when it is known already). Nobody signed in: the public rows only.
     */
    private void applyScope(User known) {
        FirebaseUser signedIn = auth.getCurrentUser();
        if (signedIn == null) {
            store.setScope(null);
            return;
        }
        FirestoreRemoteStore.Scope scope = new FirestoreRemoteStore.Scope();
        scope.uid = signedIn.getUid();
        User user = known;
        if (user == null) {
            String userId = app.getSharedPreferences("flavium_cloud", Context.MODE_PRIVATE)
                    .getString("user_id", null);
            user = userId == null ? null : repo.getUser(userId);
        }
        if (user != null && user.active) {
            scope.role = user.role;
            scope.branchId = user.branchId;
        }
        store.setScope(scope);
    }

    private void rememberUser(String userId) {
        app.getSharedPreferences("flavium_cloud", Context.MODE_PRIVATE).edit()
                .putString("user_id", userId).apply();
    }

    // =====================================================================
    // Connecting this device
    // =====================================================================

    /** Waits. What is on the server: nothing, an unfinished upload, or a festival. */
    public RemoteStore.Dataset dataset() {
        return store.dataset();
    }

    /**
     * Waits. FIRST DEVICE: uploads this device's festival, after Faculty has confirmed
     * it. Creates (or signs in to) the Faculty's online account with the given password,
     * reserves the server, and uploads. Can be repeated after an interruption.
     */
    public void uploadFestival(User faculty, String password) {
        if (faculty == null || !Role.FACULTY.equals(faculty.role)) {
            throw new PermissionDeniedException("Only Faculty can upload the festival.");
        }
        String uid = createOrSignIn(faculty.email, password);
        applyScope(faculty);
        long device = engine.deviceId();
        if (!store.claim(device)) {
            throw new SyncException("The server already has a festival, or another device is "
                    + "uploading one. This device has to join it instead.");
        }
        Map<String, Object> link = new HashMap<>();
        link.put("userId", faculty.id);
        await(linkDoc(uid).set(link));
        Map<String, Object> session = new HashMap<>();
        session.put("dev", device);
        await(sessionDoc(uid).set(session));
        rememberUser(faculty.id);
        engine.uploadAsFestival();
        startListening();
        notifyObservers();
    }

    /**
     * Waits. EVERY OTHER DEVICE: replaces this device's festival data with the shared
     * one, after the person has confirmed it. A copy of the local database is kept
     * first and returned; if the copy fails, nothing is replaced.
     */
    public File joinFestival() {
        File backup = LocalBackup.copy(app, RepositoryProvider.database(app));
        auth.signOut();
        applyScope(null);
        engine.joinFestival();
        startListening();
        notifyObservers();
        return backup;
    }

    // =====================================================================
    // Signing in and out
    // =====================================================================

    /**
     * Waits. Online login. Firebase checks the password; the festival says which account
     * that is; the role must be the one chosen on the login screen. An account is signed
     * in on one device at a time: a second device is refused with SessionActiveException
     * unless takeOver is true, in which case the first device is signed out.
     */
    public User signIn(String email, String password, String expectedRole, boolean takeOver) {
        String uid;
        try {
            uid = await(auth.signInWithEmailAndPassword(email.trim(), password))
                    .getUser().getUid();
        } catch (ValidationException e) {
            throw new ValidationException("Wrong email or password.");
        }
        try {
            DocumentSnapshot link = await(linkDoc(uid).get(Source.SERVER));
            String userId = link.exists() ? link.getString("userId") : null;
            if (userId == null) {
                throw new PermissionDeniedException("This sign-in is not an account of the "
                        + "festival yet. Ask Faculty to add it.");
            }
            // The public copy of the account says which role and branch it has.
            DocumentSnapshot account = await(store.meta().collection("pub")
                    .document("users:" + userId).get(Source.SERVER));
            Object role = account.exists() ? account.get("v.role") : null;
            if (role == null || !String.valueOf(role).equals(expectedRole)) {
                throw new PermissionDeniedException("This account is not registered as "
                        + Role.label(expectedRole) + ". Go back and choose the role you were "
                        + "given.");
            }

            final long device = engine.deviceId();
            final DocumentReference session = sessionDoc(uid);
            final boolean replace = takeOver;
            boolean free = await(firestore.runTransaction(transaction -> {
                DocumentSnapshot current = transaction.get(session);
                Long holder = current.exists() ? current.getLong("dev") : null;
                if (holder != null && holder != device && !replace) {
                    return false;
                }
                Map<String, Object> mine = new HashMap<>();
                mine.put("dev", device);
                mine.put("name", Build.MODEL);
                transaction.set(session, mine);
                return true;
            }));
            if (!free) {
                throw new SessionActiveException("This account is signed in on another "
                        + "device. Log out there first, or sign that device out and "
                        + "continue here.");
            }

            FirestoreRemoteStore.Scope scope = new FirestoreRemoteStore.Scope();
            scope.uid = uid;
            scope.role = expectedRole;
            Object branch = account.get("v.branch_id");
            scope.branchId = branch == null ? null : String.valueOf(branch);
            store.setScope(scope);
            // The account may see more now than this device has: fetch it.
            engine.reloadAll();
            rememberUser(userId);
            User user = repo.loginVerified(userId, expectedRole, Build.MODEL);
            startListening();
            return user;
        } catch (ArenaException e) {
            // Not signed in after all: do not leave a half-open Firebase sign-in behind.
            auth.signOut();
            applyScope(null);
            throw e;
        }
    }

    /** Signs out of Firebase and frees the account for another device. Does not wait. */
    public void signOut() {
        if (!isConfigured()) {
            return;
        }
        final FirebaseUser user = auth.getCurrentUser();
        worker.execute(() -> {
            stopListening();
            if (user != null) {
                try {
                    await(sessionDoc(user.getUid()).delete());
                } catch (ArenaException e) {
                    Log.w(TAG, "Session could not be freed: " + e.getMessage());
                }
            }
            auth.signOut();
            rememberUser(null);
            applyScope(null);
            if (isConnected()) {
                startListening();
            }
        });
    }

    /**
     * Waits. Gives an account of the festival an online login (Faculty, or a Core for
     * the roles a Core manages; the server checks). The new login is created on a second
     * Firebase connection so the person doing it stays signed in.
     */
    public void createLogin(String email, String password, String userId) {
        FirebaseApp maker = null;
        for (FirebaseApp existing : FirebaseApp.getApps(app)) {
            if (existing.getName().equals("account-maker")) {
                maker = existing;
            }
        }
        if (maker == null) {
            maker = FirebaseApp.initializeApp(app, FirebaseApp.getInstance().getOptions(),
                    "account-maker");
            String emulator = app.getString(R.string.firebase_emulator_host);
            boolean debuggable = (app.getApplicationInfo().flags
                    & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (debuggable && !emulator.isEmpty()) {
                FirebaseAuth.getInstance(maker).useEmulator(emulator, 9099);
            }
        }
        FirebaseAuth second = FirebaseAuth.getInstance(maker);
        String uid;
        try {
            uid = await(second.createUserWithEmailAndPassword(email.trim(), password))
                    .getUser().getUid();
        } finally {
            second.signOut();
        }
        Map<String, Object> link = new HashMap<>();
        link.put("userId", userId);
        await(linkDoc(uid).set(link));
    }

    /** Waits. Sends the person an email with which they choose a new password themselves. */
    public void sendPasswordReset(String email) {
        await(auth.sendPasswordResetEmail(email.trim()));
    }

    private String createOrSignIn(String email, String password) {
        try {
            return await(auth.createUserWithEmailAndPassword(email, password)).getUser().getUid();
        } catch (SyncException alreadyThere) {
            try {
                return await(auth.signInWithEmailAndPassword(email, password)).getUser().getUid();
            } catch (ValidationException e) {
                throw new ValidationException("An online account for " + email + " already "
                        + "exists and this is not its password.");
            }
        }
    }

    // =====================================================================
    // Waiting for Firebase
    // =====================================================================

    /** Waits for a Firebase call; failures become the app's own kinds of error. */
    private static <T> T await(Task<T> task) {
        try {
            return Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RemoteStore.Unavailable("The server did not answer in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteStore.Unavailable("The request was interrupted.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FirebaseAuthUserCollisionException) {
                throw new SyncException("An online account with this email already exists.");
            }
            if (cause instanceof FirebaseNetworkException) {
                throw new RemoteStore.Unavailable("No connection to the server.");
            }
            if (cause instanceof FirebaseAuthException) {
                // Wrong password, unknown email, weak password: say what Firebase said.
                throw new ValidationException(String.valueOf(cause.getMessage()));
            }
            if (cause instanceof com.google.firebase.firestore.FirebaseFirestoreException) {
                com.google.firebase.firestore.FirebaseFirestoreException.Code code =
                        ((com.google.firebase.firestore.FirebaseFirestoreException) cause)
                                .getCode();
                if (code == com.google.firebase.firestore.FirebaseFirestoreException.Code
                        .PERMISSION_DENIED) {
                    throw new RemoteStore.Refused("The server does not allow this account to "
                            + "do that.");
                }
            }
            throw new RemoteStore.Unavailable(cause == null ? "The server could not be reached."
                    : String.valueOf(cause.getMessage()));
        }
    }
}
