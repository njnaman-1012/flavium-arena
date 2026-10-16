package com.example.flaviumarena.cloud;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

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

        /**
         * Only the connection or the number of waiting changes changed. Nothing on the
         * screen needs to be reloaded, so a form being filled in is left alone.
         */
        default void onSyncStatusChanged() {
        }
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
    /** Whether the phone has a network at all. Firebase itself retries quietly. */
    private volatile boolean online = true;
    /** Failed exchanges in a row; the next attempt waits longer each time. */
    private int failures;
    private final Runnable retry = this::requestSync;
    /** What the last attempt to connect found; null while nothing is known yet. */
    private volatile SyncEngine.Connection connection;
    /** When this device last asked the server whether it can connect (elapsed time). */
    private volatile long lastConnectAttempt;
    /**
     * Held while connecting, so two callers (the app starting, the login screen) can
     * never connect twice at once. Not the object's own lock: saving must never wait
     * for the network.
     */
    private final Object connectLock = new Object();
    /** True while changes are being exchanged with the server. */
    private volatile boolean syncing;
    /** Why the live updates stopped, or "" while they work. */
    private volatile String listenerProblem = "";
    /** Starts the live updates again after they stopped with an error. */
    private final Runnable relisten = () -> worker.execute(() -> {
        if (isConnected() && !observers.isEmpty()) {
            applyScope(null);
            startListening();
        }
    });

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
        // Release builds: the real project, always. Debug builds may be pointed at the
        // Firebase Local Emulator Suite (see LocalServers in app/src/debug).
        LocalServers.use(context, firestore, auth);
        SyncEngine probe = new SyncEngine(RepositoryProvider.database(context), null);
        store = new FirestoreRemoteStore(firestore, probe.deviceId());
        engine = new SyncEngine(RepositoryProvider.database(context), store);
        engine.setListener(tables -> notifyObservers());
        watchNetwork();
    }

    /**
     * Notices when the phone loses and regains its network. When it comes back, what
     * was saved meanwhile is sent at once, without anyone pressing anything.
     */
    private void watchNetwork() {
        ConnectivityManager connectivity = app.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return;
        }
        online = connectivity.getActiveNetwork() != null;
        try {
            connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    online = true;
                    synchronized (Cloud.this) {
                        failures = 0;
                    }
                    main.removeCallbacks(retry);
                    worker.execute(() -> {
                        lastConnectAttempt = 0;
                        connectQuietly();
                        requestSync();
                    });
                    notifyStatus();
                }

                @Override
                public void onLost(Network network) {
                    online = false;
                    notifyStatus();
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot watch the network", e);
        }
    }

    /** False while the phone has no network. Changes are then kept and sent later. */
    public boolean isOnline() {
        return online;
    }

    /** See SyncEngine.lastReceived: changes to a row that came from another device. */
    public long lastReceived(String table, String key) {
        return isConfigured() ? engine.lastReceived(table, key) : 0;
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

    /** What connecting found: see SyncEngine.Connection. Null while nothing is known. */
    public SyncEngine.Connection connection() {
        return isConnected() ? SyncEngine.Connection.CONNECTED : connection;
    }

    /** True while changes are being sent or received. */
    public boolean isSyncing() {
        return syncing;
    }

    /** Why the live updates from other devices stopped, or "" while they work. */
    public String listenerProblem() {
        return listenerProblem;
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

    private void notifyStatus() {
        main.post(() -> {
            for (Observer observer : observers) {
                observer.onSyncStatusChanged();
            }
        });
    }

    /**
     * After a failed exchange, tries again by itself while the app is open: after 5
     * seconds, then 10, 20, 40, and every minute from then on. A success, or the
     * network coming back, starts the count again.
     */
    private void scheduleRetry(boolean succeeded) {
        main.removeCallbacks(retry);
        if (succeeded || engine.pendingCount() == 0) {
            synchronized (this) {
                failures = 0;
            }
            return;
        }
        if (observers.isEmpty()) {
            return; // the app is in the background; it tries again when it is opened
        }
        long delay;
        synchronized (this) {
            failures++;
            delay = Math.min(60_000L, 5_000L << Math.min(failures - 1, 4));
        }
        main.postDelayed(retry, delay);
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

    /**
     * A screen came to the front. A device that is not part of the online festival yet
     * joins it by itself; a connected one listens for changes and exchanges what is
     * waiting. Nobody has to press anything.
     */
    public void onForeground() {
        if (!isConfigured()) {
            return;
        }
        worker.execute(() -> {
            connectQuietly();
            if (isConnected()) {
                applyScope(null);
                startListening();
                requestSync();
            }
        });
    }

    /**
     * Connects if it can without asking anybody: a new installation joins the online
     * festival, an interrupted set-up continues. Asks the server at most every 30
     * seconds while that finds nothing to connect to. Runs on the worker thread.
     */
    private void connectQuietly() {
        if (isConnected()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastConnectAttempt != 0 && now - lastConnectAttempt < 30_000) {
            return;
        }
        lastConnectAttempt = now;
        try {
            connect(false);
        } catch (RuntimeException e) {
            Log.w(TAG, "Not connected yet: " + e.getMessage());
        }
        notifyStatus();
    }

    /**
     * Waits. Becomes part of the online festival if possible (see SyncEngine.connect).
     * replaceLocalData is true only when the person decided that this device's own
     * data may be replaced; a copy of the database is kept first.
     */
    public SyncEngine.Connection connect(boolean replaceLocalData) {
        if (!isConfigured()) {
            return null;
        }
        synchronized (connectLock) {
            return connectLocked(replaceLocalData);
        }
    }

    private SyncEngine.Connection connectLocked(boolean replaceLocalData) {
        boolean wasConnected = isConnected();
        final boolean hadOwnData = engine.hasLocalFestival();
        SyncEngine.Connection result;
        if (engine.status().link == SyncEngine.LINK_UPLOADING) {
            // This device was setting up online sync: carry on where it stopped. Only the
            // Faculty sign-in that started it can.
            if (auth.getCurrentUser() == null) {
                connection = SyncEngine.Connection.SETTING_UP;
                return connection;
            }
            applyScope(null);
            engine.uploadAsFestival();
            result = SyncEngine.Connection.CONNECTED;
        } else {
            result = engine.connect(replaceLocalData, () -> {
                if (hadOwnData) {
                    LocalBackup.copy(app, RepositoryProvider.database(app));
                }
                // The device starts as a public device; whoever logs in next gets the
                // rows their account may see.
                auth.signOut();
                rememberUser(null);
                applyScope(null);
            });
        }
        connection = result;
        if (result == SyncEngine.Connection.CONNECTED && !wasConnected) {
            if (hadOwnData && engine.status().link == SyncEngine.LINK_CONNECTED
                    && auth.getCurrentUser() == null) {
                notice("This device now uses the online festival. A copy of its earlier "
                        + "data was kept. Log in again with your online account.");
            }
            startListening();
            notifyObservers();
        }
        return result;
    }

    /**
     * Waits. Before a login: connect if possible, so the server checks the password. Null
     * when the server cannot be reached; the login then works on this device's own data.
     */
    public SyncEngine.Connection connectForLogin() {
        try {
            lastConnectAttempt = SystemClock.elapsedRealtime();
            return connect(false);
        } catch (RemoteStore.Unavailable e) {
            return null;
        }
    }

    /** The last screen went away: stop listening (the system may stop the app anyway). */
    public void onBackground() {
        if (!isConfigured() || !observers.isEmpty()) {
            return;
        }
        main.removeCallbacks(retry);
        main.removeCallbacks(relisten);
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
            boolean succeeded = false;
            syncing = true;
            notifyStatus();
            try {
                succeeded = engine.sync();
            } catch (RuntimeException e) {
                // Shown on the status line ("Sync temporarily unavailable"), retried below.
                Log.w(TAG, "Synchronisation failed", e);
            } finally {
                syncing = false;
            }
            scheduleRetry(succeeded);
            notifyStatus();
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

    private void startListening() {
        stopListening();
        rowListener = store.listen(engine.cursor(), new FirestoreRemoteStore.ChangeSink() {
            @Override
            public void onChanges(final List<Change> changes) {
                // The listener reports on the main thread; the database work must not run there.
                worker.execute(() -> {
                    try {
                        engine.applyRemote(changes);
                        followAccount();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Applying server changes failed", e);
                    }
                });
            }

            @Override
            public void onError(String message) {
                // A listener that failed has stopped. Say so on the status screen (not in
                // a pop-up every time) and start it again in half a minute.
                listenerProblem = message;
                notifyStatus();
                main.removeCallbacks(relisten);
                main.postDelayed(relisten, 30_000);
            }
        });
        listenerProblem = "";
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
            scope.sportIds = new ArrayList<>(user.coachSportIds);
        }
        store.setScope(scope);
    }

    /**
     * What this device may read depends on the signed-in account: its role, its branch
     * (Branch CR) and its sports (Coach). When another device changes any of these, what
     * this device asks the server for changes with it, and rows that have just become
     * visible are fetched from the beginning.
     */
    private void followAccount() {
        FirestoreRemoteStore.Scope current = store.scope();
        if (current == null || current.uid == null) {
            return; // nobody signed in: the public rows, which never change scope
        }
        String userId = app.getSharedPreferences("flavium_cloud", Context.MODE_PRIVATE)
                .getString("user_id", null);
        User user = userId == null ? null : repo.getUser(userId);
        boolean usable = user != null && user.active;
        String role = usable ? user.role : null;
        String branch = usable ? user.branchId : null;
        List<String> sports = usable ? user.coachSportIds : new ArrayList<>();
        if (same(role, current.role) && same(branch, current.branchId)
                && sports.containsAll(current.sportIds) && current.sportIds.containsAll(sports)) {
            return;
        }
        applyScope(user);
        try {
            engine.reloadAll();
        } catch (ArenaException e) {
            Log.w(TAG, "Reloading after a change of the account failed: " + e.getMessage());
        }
        startListening();
        notifyObservers();
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
     * Waits. ONE-TIME SET-UP of online sync for the whole festival, while nothing is
     * online yet: this device's festival becomes the online one, after Faculty has
     * confirmed it. Creates (or signs in to) the Faculty's online account with the given
     * password, reserves the server, and copies the data up. If it is interrupted it
     * continues by itself the next time the app is opened. Every other device then
     * connects on its own; this one is not special afterwards.
     */
    public void uploadFestival(User faculty, String password) {
        if (faculty == null || !Role.FACULTY.equals(faculty.role)) {
            throw new PermissionDeniedException("Only Faculty can upload the festival.");
        }
        if (engine.status().link == SyncEngine.LINK_NONE) {
            // The festival is about to be copied up from here: keep a copy of it first.
            LocalBackup.copy(app, RepositoryProvider.database(app));
        }
        String uid = createOrSignIn(faculty.email, password);
        applyScope(faculty);
        long device = engine.deviceId();
        if (!store.claim(device)) {
            throw new SyncException("The festival is already online, or is being put online "
                    + "from another device. This device connects to it by itself.");
        }
        Map<String, Object> link = new HashMap<>();
        link.put("userId", faculty.id);
        await(linkDoc(uid).set(link));
        Map<String, Object> session = new HashMap<>();
        session.put("dev", device);
        await(sessionDoc(uid).set(session));
        rememberUser(faculty.id);
        engine.uploadAsFestival();
        connection = SyncEngine.Connection.CONNECTED;
        startListening();
        notifyObservers();
    }

    /**
     * Waits. Only for a device that holds festival data of its own while the festival is
     * online (LOCAL_DATA_IN_THE_WAY), after the person decided to use the online one. A
     * new installation never needs this: it connects by itself.
     */
    public void useOnlineFestival() {
        connect(true);
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
            // The account may see more now than this device has: fetch it. If the previous
            // account's data could not be cleared at its logout, replace everything with
            // what this account may see instead.
            boolean startAfresh = app.getSharedPreferences("flavium_cloud", Context.MODE_PRIVATE)
                    .getBoolean("replace_at_login", false);
            if (startAfresh && engine.replaceWithServerView()) {
                app.getSharedPreferences("flavium_cloud", Context.MODE_PRIVATE).edit()
                        .putBoolean("replace_at_login", false).apply();
            } else {
                engine.reloadAll();
            }
            User local = repo.getUser(userId);
            if (local != null && Role.COACH.equals(expectedRole)
                    && !local.coachSportIds.isEmpty()) {
                // Only now is it known which sports this Coach has.
                scope.sportIds = new ArrayList<>(local.coachSportIds);
                store.setScope(scope);
                engine.reloadAll();
            }
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
                // What this account saved here goes out under its own name first; the
                // next account on this device could not send it.
                try {
                    engine.sync();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Last synchronisation before signing out failed", e);
                }
                try {
                    await(sessionDoc(user.getUid()).delete());
                } catch (ArenaException e) {
                    Log.w(TAG, "Session could not be freed: " + e.getMessage());
                }
            }
            auth.signOut();
            rememberUser(null);
            applyScope(null);
            // Nothing of the previous account's private data stays behind: the device
            // keeps the public festival only. If changes are still waiting (no network),
            // that happens at the next login instead, so nothing is lost.
            boolean cleared = false;
            try {
                cleared = engine.replaceWithServerView();
            } catch (RuntimeException e) {
                Log.w(TAG, "Keeping only the public data failed: " + e.getMessage());
            }
            app.getSharedPreferences("flavium_cloud", Context.MODE_PRIVATE).edit()
                    .putBoolean("replace_at_login", !cleared).apply();
            if (isConnected()) {
                startListening();
                notifyObservers();
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
            LocalServers.use(app, null, FirebaseAuth.getInstance(maker));
        }
        // The server links a sign-in only to an account it already knows.
        if (isConnected() && !engine.sync()) {
            throw new RemoteStore.Unavailable("No internet connection. Try again when you are "
                    + "online.");
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
            throw new RemoteStore.Unavailable("The connection is too slow right now. Try again "
                    + "in a moment.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteStore.Unavailable("The request was interrupted.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FirebaseAuthUserCollisionException) {
                throw new SyncException("An online account with this email already exists.");
            }
            if (cause instanceof FirebaseNetworkException) {
                throw new RemoteStore.Unavailable("No internet connection. Try again when you "
                        + "are online.");
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
                    throw new RemoteStore.Refused("Your account is not allowed to do that.");
                }
            }
            Log.w(TAG, "Request failed", cause);
            throw new RemoteStore.Unavailable("Sync temporarily unavailable. We'll retry "
                    + "automatically.");
        }
    }
}
