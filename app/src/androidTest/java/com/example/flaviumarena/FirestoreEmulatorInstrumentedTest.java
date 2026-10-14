package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.DatabaseUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.flaviumarena.cloud.FirestoreRemoteStore;
import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.DbHelper;
import com.example.flaviumarena.data.DevData;
import com.example.flaviumarena.data.SqliteArenaRepository;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.sync.Change;
import com.example.flaviumarena.sync.PublicView;
import com.example.flaviumarena.sync.RemoteStore;
import com.example.flaviumarena.sync.SyncEngine;
import com.example.flaviumarena.util.DateUtil;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The real Firestore adapter and the real security rules, against the Firebase Local
 * Emulator Suite running on the development computer. Nothing here touches the live
 * Firebase project and nothing goes over the internet.
 *
 * Start the emulators in the project folder and forward their ports to the device first:
 *     firebase emulators:start --only firestore,auth --project demo-flavium
 *     adb reverse tcp:8080 tcp:8080
 *     adb reverse tcp:9099 tcp:9099
 * Without them every test in this class is skipped.
 *
 * Each "device" is a separate local database with its own Firebase sign-in.
 */
@RunWith(AndroidJUnit4.class)
public class FirestoreEmulatorInstrumentedTest {

    private static final String HOST = "127.0.0.1";  // forwarded to the computer by "adb reverse"
    private static final String PROJECT = "demo-flavium";
    private static final String PASSWORD = "Temp@12345";

    /** One installation: its database, its sign-in and its connection to the server. */
    private static class Device {
        FirebaseFirestore firestore;
        FirebaseAuth auth;
        DbHelper helper;
        ArenaRepository repo;
        FirestoreRemoteStore store;
        SyncEngine sync;
        String uid;
        FirestoreRemoteStore.Scope scope;
    }

    private Context context;
    private final List<Device> devices = new ArrayList<>();
    private Device a;          // Faculty; holds the festival and uploads it
    private User facultyA;
    private Category sevens;
    private Category singles;

    // ----- Setting up -----

    private static boolean emulatorsRunning() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + HOST + ":8080/")
                    .openConnection();
            c.setConnectTimeout(2000);
            c.getResponseCode();
            c.disconnect();
            return true;
        } catch (Exception e) {
            android.util.Log.w("FlaviumArena", "Firebase emulators not reachable: " + e);
            return false;
        }
    }

    private static void http(String method, String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer owner");
        c.getResponseCode();
        c.disconnect();
    }

    private Device device(String name, boolean withFestival) {
        FirebaseApp app = null;
        for (FirebaseApp existing : FirebaseApp.getApps(context)) {
            if (existing.getName().equals(name)) {
                app = existing;
            }
        }
        Device d = new Device();
        if (app == null) {
            app = FirebaseApp.initializeApp(context, new FirebaseOptions.Builder()
                    .setProjectId(PROJECT).setApplicationId("1:1:android:1")
                    .setApiKey("emulator-only").build(), name);
            FirebaseFirestore.getInstance(app).useEmulator(HOST, 8080);
            FirebaseAuth.getInstance(app).useEmulator(HOST, 9099);
        }
        d.firestore = FirebaseFirestore.getInstance(app);
        d.auth = FirebaseAuth.getInstance(app);
        try {
            Tasks.await(d.firestore.enableNetwork());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        d.auth.signOut();
        d.helper = new DbHelper(context, null, withFestival);
        d.repo = new SqliteArenaRepository(d.helper);
        SyncEngine probe = new SyncEngine(d.helper, null);
        d.store = new FirestoreRemoteStore(d.firestore, probe.deviceId());
        d.sync = new SyncEngine(d.helper, d.store);
        devices.add(d);
        return d;
    }

    /** Creates a sign-in for an email address on a device and signs in there. */
    private void signUp(Device d, String email) throws Exception {
        d.uid = Tasks.await(d.auth.createUserWithEmailAndPassword(email, PASSWORD))
                .getUser().getUid();
        d.scope = new FirestoreRemoteStore.Scope();
        d.scope.uid = d.uid;
        d.store.setScope(d.scope);
    }

    /**
     * Being signed in on this device is what "one session per account" checks. The
     * server accepts it only from an account of the festival, so it follows the link.
     */
    private void openSession(Device d) throws Exception {
        Map<String, Object> session = new HashMap<>();
        session.put("dev", d.sync.deviceId());
        Tasks.await(d.firestore.document("festivals/main/sessions/" + d.uid).set(session));
    }

    /** Faculty links a sign-in to an account of the festival. */
    private void link(Device d, String userId, String role, String branchId) throws Exception {
        Map<String, Object> link = new HashMap<>();
        link.put("userId", userId);
        Tasks.await(a.firestore.document("festivals/main/links/" + d.uid).set(link));
        d.scope.role = role;
        d.scope.branchId = branchId;
        openSession(d);
    }

    private String userId(String email) {
        return DatabaseUtils.stringForQuery(a.helper.getReadableDatabase(),
                "SELECT id FROM users WHERE email = ?", new String[]{email});
    }

    /** A local session on a device for an account that arrived from the server. */
    private User localUser(Device d, String userId, String role) {
        return d.repo.loginVerified(userId, role, "test device");
    }

    @Before
    public void setUp() throws Exception {
        assumeTrue("The Firebase emulators are not running", emulatorsRunning());
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        http("DELETE", "http://" + HOST + ":8080/emulator/v1/projects/" + PROJECT
                + "/databases/(default)/documents");
        http("DELETE", "http://" + HOST + ":9099/emulator/v1/projects/" + PROJECT + "/accounts");

        // Device A has the festival and a Faculty account, and uploads it.
        a = device("device-a", true);
        facultyA = a.repo.createFirstFaculty("Test Faculty", "faculty@flavium.test", PASSWORD,
                "faculty@flavium.test");
        signUp(a, "faculty@flavium.test");
        a.scope.role = Role.FACULTY;
        assertTrue(a.store.claim(a.sync.deviceId()));
        Map<String, Object> link = new HashMap<>();
        link.put("userId", facultyA.id);
        Tasks.await(a.firestore.document("festivals/main/links/" + a.uid).set(link));
        openSession(a);
        a.sync.uploadAsFestival();

        for (Sport s : a.repo.getSports()) {
            for (Category c : a.repo.getCategories(s.id)) {
                if (c.name.equals("Men's 7-a-side")) {
                    sevens = c;
                } else if (c.name.equals("Women's Singles")) {
                    singles = c;
                }
            }
        }
    }

    @After
    public void tearDown() {
        for (Device d : devices) {
            d.helper.close();
        }
    }

    private Device join(String name, String email, String role, String branchId) throws Exception {
        Device d = device(name, false);
        signUp(d, email);
        link(d, userId(email), role, branchId);
        d.sync.joinFestival();
        return d;
    }

    private long count(Device d, String table, String where) {
        return DatabaseUtils.queryNumEntries(d.helper.getReadableDatabase(), table, where);
    }

    private Match liveMatch(Device d) {
        for (Match m : d.repo.getMatches(sevens.id)) {
            if (Match.STATUS_LIVE.equals(m.status)) {
                return m;
            }
        }
        throw new IllegalStateException("No live match");
    }

    private interface Action {
        void run();
    }

    private void refused(Action action) {
        try {
            action.run();
            fail("The server should have refused this");
        } catch (RemoteStore.Refused expected) {
            // correct
        }
    }

    /** The server must accept this change and give the row a higher revision. */
    private void accepted(Device d, Change change) {
        long before = change.rev;
        assertTrue(d.store.push(change, before) > before);
    }

    /** The server's copy of a row with some columns changed, ready to be sent by anyone. */
    private Change edited(String table, String key, Object... columnsAndValues) {
        Change current = a.store.get(table, key);
        assertNotNull(table + " " + key, current);
        Map<String, Object> values = new LinkedHashMap<>(current.values);
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            values.put((String) columnsAndValues[i], columnsAndValues[i + 1]);
        }
        Change change = new Change(table, key, false, values);
        change.rev = current.rev;
        change.branch = current.branch;
        change.sport = current.sport;
        return change;
    }

    // ----- Upload, join and what each kind of device receives -----

    @Test
    public void aCoreReceivesEverythingAndThePublicOnlyThePublicPart() throws Exception {
        Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        for (String table : new String[]{"branches", "sports", "categories", "registrations",
                "matches", "match_periods", "score_audit"}) {
            assertEquals(table, count(a, table, null), count(core, table, null));
        }
        assertTrue("A Core sees registrations that are waiting",
                count(core, "registrations", "status = 'PENDING'") > 0);
        assertTrue(count(core, "registrations", "contact_phone <> ''") > 0);
        assertEquals(1, count(core, "users", "email = '" + DevData.CORE_EMAIL + "'"));
        assertEquals("Passwords are never on the server", 0,
                count(core, "users", "password_hash <> ''"));

        // A phone that nobody is signed in on.
        Device visitor = device("device-public", false);
        visitor.sync.joinFestival();
        assertEquals(count(a, "matches", null), count(visitor, "matches", null));
        assertEquals(count(a, "registrations", "status = 'APPROVED'"),
                count(visitor, "registrations", null));
        assertEquals(0, count(visitor, "registrations", "contact_phone <> ''"));
        assertEquals(0, count(visitor, "users", "email LIKE '%@flavium.test'"));
        assertEquals(0, count(visitor, "score_audit", null));
        assertEquals(0, count(visitor, "admin_audit", null));
        // Fixtures still show who scores them.
        assertTrue(count(visitor, "users", "name = 'Test Asoo Core'") == 1);
        // Standings are the same everywhere, worked out from the same results.
        assertEquals(a.repo.getStandings(sevens.id).get(0).name,
                visitor.repo.getStandings(sevens.id).get(0).name);
    }

    @Test
    public void aBranchCrReceivesThePrivateRegistrationsOfTheirOwnBranchOnly() throws Exception {
        User crOnA = a.repo.getUser(userId(DevData.CAPTAIN_EMAIL));
        Device cr = join("device-cr", DevData.CAPTAIN_EMAIL, Role.CAPTAIN, crOnA.branchId);
        String own = "branch_id = '" + crOnA.branchId + "'";
        assertEquals(count(a, "registrations", own), count(cr, "registrations", own));
        assertTrue(count(cr, "registrations", own + " AND status = 'PENDING'") > 0);
        assertTrue(count(cr, "registrations", own + " AND contact_phone <> ''") > 0);
        // Of other branches: approved entries only, without phone numbers.
        assertEquals(0, count(cr, "registrations", "NOT " + own + " AND status <> 'APPROVED'"));
        assertEquals(0, count(cr, "registrations", "NOT " + own + " AND contact_phone <> ''"));
    }

    // ----- Changes travel through the server -----

    @Test
    public void aScoreChangedOnOneDeviceArrivesOnAnotherWithoutAsking() throws Exception {
        final Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        final CountDownLatch arrived = new CountDownLatch(1);
        final Match live = liveMatch(a);
        ListenerRegistration listening = core.store.listen(core.sync.cursor(),
                new FirestoreRemoteStore.ChangeSink() {
                    @Override
                    public void onChanges(List<Change> changes) {
                        core.sync.applyRemote(changes);
                        Match now = core.repo.getMatch(live.id);
                        if (now.scoreA != null && now.scoreA == 4) {
                            arrived.countDown();
                        }
                    }

                    @Override
                    public void onError(String message) {
                    }
                });
        try {
            a.repo.updateScore(facultyA, live.id, live.version, 4, 2, "", Match.STATUS_LIVE);
            assertTrue(a.sync.sync());
            assertTrue("The change reached the other device by itself",
                    arrived.await(20, TimeUnit.SECONDS));
            Match onCore = core.repo.getMatch(live.id);
            assertEquals(Integer.valueOf(2), onCore.scoreB);
            assertEquals(a.repo.getMatch(live.id).version, onCore.version);
            assertEquals(0, core.sync.pendingCount());
        } finally {
            listening.remove();
        }
    }

    @Test
    public void twoDevicesChangingOneMatchEndWithOneScoreAndOneHistory() throws Exception {
        Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        User coreUser = localUser(core, userId(DevData.CORE_EMAIL), Role.CORE);
        Match live = liveMatch(a);
        a.repo.updateScore(facultyA, live.id, live.version, 5, 0, "", Match.STATUS_LIVE);
        core.repo.updateScore(coreUser, live.id, core.repo.getMatch(live.id).version, 0, 5, "",
                Match.STATUS_LIVE);
        a.sync.pushPending();
        core.sync.pushPending();          // refused: the server's copy has moved on
        assertFalse(core.sync.takeNotices().isEmpty());
        a.sync.pull();
        core.sync.pull();
        assertEquals(Integer.valueOf(5), core.repo.getMatch(live.id).scoreA);
        assertEquals(Integer.valueOf(0), core.repo.getMatch(live.id).scoreB);
        assertEquals(a.repo.getScoreAudit(facultyA, live.id).size(),
                core.repo.getScoreAudit(coreUser, live.id).size());
        assertEquals(0, core.sync.pendingCount());
    }

    @Test
    public void aRegistrationMadeByABranchCrReachesFacultyAndApprovalComesBack() throws Exception {
        User crOnA = a.repo.getUser(userId(DevData.CAPTAIN_EMAIL));
        Device cr = join("device-cr", DevData.CAPTAIN_EMAIL, Role.CAPTAIN, crOnA.branchId);
        User crUser = localUser(cr, crOnA.id, Role.CAPTAIN);
        Registration r = new Registration();
        r.categoryId = sevens.id;
        r.branchId = crOnA.branchId;
        r.year = 1;
        r.contactName = "Contact";
        r.contactPhone = "9000000077";
        r.members = "A\nB\nC\nD\nE\nF\nG";
        String id = cr.repo.saveRegistration(crUser, r);
        assertTrue(cr.sync.sync());
        assertTrue(cr.sync.takeNotices().isEmpty());

        a.sync.pull();
        assertEquals(Registration.STATUS_PENDING, a.repo.getRegistration(facultyA, id).status);
        a.repo.setRegistrationStatus(facultyA, id, Registration.STATUS_APPROVED);
        assertTrue(a.sync.sync());
        cr.sync.pull();
        assertEquals(Registration.STATUS_APPROVED, cr.repo.getRegistration(crUser, id).status);

        // Now that it is approved the public can see it, without the phone number.
        Device visitor = device("device-public", false);
        visitor.sync.joinFestival();
        assertEquals(1, count(visitor, "registrations", "id = '" + id + "'"));
        assertEquals(0, count(visitor, "registrations", "contact_phone <> ''"));
    }

    // ----- What the server refuses -----

    @Test
    public void peopleWithoutAnAccountCannotWriteOrReadPrivateRows() throws Exception {
        final Match live = liveMatch(a);
        // Nobody signed in.
        final Device visitor = device("device-public", false);
        refused(() -> visitor.store.push(edited("matches", live.id, "score_a", 99L),
                a.store.get("matches", live.id).rev));
        // Pretending to be Faculty without signing in does not open the private rows.
        FirestoreRemoteStore.Scope pretend = new FirestoreRemoteStore.Scope();
        pretend.role = Role.FACULTY;
        visitor.store.setScope(pretend);
        refused(() -> visitor.store.changesSince(0));

        // Signed in to Firebase, but not an account of the festival.
        final Device stranger = device("device-stranger", false);
        signUp(stranger, "stranger@flavium.test");
        refused(() -> stranger.store.push(edited("matches", live.id, "score_a", 99L),
                a.store.get("matches", live.id).rev));
        stranger.scope.role = Role.FACULTY;
        refused(() -> stranger.store.changesSince(0));
        // A stranger cannot make themselves an account either.
        try {
            Map<String, Object> link = new HashMap<>();
            link.put("userId", facultyA.id);
            Tasks.await(stranger.firestore.document("festivals/main/links/" + stranger.uid)
                    .set(link));
            fail("A stranger linked themselves to the Faculty account");
        } catch (Exception expected) {
            // correct
        }
        assertEquals("Nothing changed", liveMatch(a).scoreA, a.store.get("matches", live.id)
                .values.get("score_a") == null ? null
                : Integer.valueOf(((Long) a.store.get("matches", live.id).values.get("score_a"))
                .intValue()));
    }

    @Test
    public void aBranchCrIsHeldToTheirOwnBranchByTheServer() throws Exception {
        User crOnA = a.repo.getUser(userId(DevData.CAPTAIN_EMAIL));
        final Device cr = join("device-cr", DevData.CAPTAIN_EMAIL, Role.CAPTAIN, crOnA.branchId);
        String ownPending = DatabaseUtils.stringForQuery(a.helper.getReadableDatabase(),
                "SELECT id FROM registrations WHERE branch_id = ? AND status = 'PENDING'",
                new String[]{crOnA.branchId});
        String otherPending = DatabaseUtils.stringForQuery(a.helper.getReadableDatabase(),
                "SELECT id FROM registrations WHERE branch_id <> ? AND status = 'PENDING'",
                new String[]{crOnA.branchId});
        String ownApproved = DatabaseUtils.stringForQuery(a.helper.getReadableDatabase(),
                "SELECT id FROM registrations WHERE branch_id = ? AND status = 'APPROVED'",
                new String[]{crOnA.branchId});

        // Another branch's registration.
        final Change other = edited("registrations", otherPending, "contact_name", "Changed");
        refused(() -> cr.store.push(other, other.rev));
        // Approving their own.
        final Change approve = edited("registrations", ownPending, "status", "APPROVED");
        refused(() -> cr.store.push(approve, approve.rev));
        // Changing an approved team (anything but its logo).
        final Change rename = edited("registrations", ownApproved, "contact_name", "Changed");
        refused(() -> cr.store.push(rename, rename.rev));
        // Moving their registration to another branch.
        final Change move = edited("registrations", ownPending, "branch_id", other.branch);
        move.branch = other.branch;
        refused(() -> cr.store.push(move, move.rev));
        // Scores, sports and accounts are not theirs at all.
        final Change score = edited("matches", liveMatch(a).id, "score_a", 9L);
        refused(() -> cr.store.push(score, score.rev));

        // What they may do: correct their own waiting registration, and change the logo
        // of an approved one.
        Change fix = edited("registrations", ownPending, "contact_name", "Corrected");
        accepted(cr, fix);
        Change logo = edited("registrations", ownApproved, "logo_path", "logo.png");
        accepted(cr, logo);
    }

    @Test
    public void anAsooCoreIsHeldToTheirAssignedMatchesByTheServer() throws Exception {
        final Device asoo = join("device-asoo", DevData.ASOO_EMAIL, Role.ASOO_CORE, null);
        String asooId = userId(DevData.ASOO_EMAIL);
        Match assigned = liveMatch(a);
        assertEquals(asooId, assigned.assignedTo);
        String unassigned = DatabaseUtils.stringForQuery(a.helper.getReadableDatabase(),
                "SELECT id FROM matches WHERE assigned_to IS NULL LIMIT 1", null);

        Change ok = edited("matches", assigned.id, "score_a", 3L);
        accepted(asoo, ok);

        final Change notMine = edited("matches", unassigned, "score_a", 3L);
        refused(() -> asoo.store.push(notMine, notMine.rev));
        // Making a result official is not for an Asoo Core.
        final Change official = edited("matches", assigned.id, "is_final", 1L);
        refused(() -> asoo.store.push(official, official.rev));
        // Nor is handing the match to someone else, or touching a sport or a setting.
        final Change reassign = edited("matches", assigned.id, "assigned_to", facultyA.id);
        refused(() -> asoo.store.push(reassign, reassign.rev));
        final Change setting = edited("settings", "core_can_finalise", "value", 0L);
        refused(() -> asoo.store.push(setting, setting.rev));
    }

    @Test
    public void aCoachIsHeldToTheirSportsByTheServer() throws Exception {
        String football = sevens.sportId;
        String coachId = a.repo.createUser(facultyA, "Football Coach", "coach@flavium.test",
                PASSWORD, Role.COACH, null);
        a.repo.setCoachSports(facultyA, coachId, Arrays.asList(football));
        assertTrue(a.sync.sync());
        final Device coach = joinAsCoach(football);

        // A fixture of their sport.
        Match own = liveMatch(a);
        Change ok = edited("matches", own.id, "venue", "Ground 2");
        accepted(coach, ok);
        // A Coach makes results of their sport official, like Faculty.
        Change official = edited("matches", own.id, "is_final", 1L);
        accepted(coach, official);

        // A fixture of another sport.
        String otherMatch = a.repo.getMatches(singles.id).get(0).id;
        final Change other = edited("matches", otherMatch, "venue", "Elsewhere");
        refused(() -> coach.store.push(other, other.rev));
        // Lying about which sport the fixture belongs to does not help.
        final Change lie = edited("matches", otherMatch, "venue", "Elsewhere");
        lie.sport = football;
        refused(() -> coach.store.push(lie, lie.rev));
        // Sports, branches, settings and accounts are not a Coach's.
        final Change sport = edited("sports", football, "description", "Mine now");
        refused(() -> coach.store.push(sport, sport.rev));
        final Change account = edited("users", coachId, "role", "FACULTY");
        refused(() -> coach.store.push(account, account.rev));
    }

    @Test
    public void aCoreCannotTouchSettingsOrSeniorAccounts() throws Exception {
        final Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        final Change setting = edited("settings", "core_can_finalise", "value", 0L);
        refused(() -> core.store.push(setting, setting.rev));
        final Change promote = edited("users", userId(DevData.CORE_EMAIL), "role", "FACULTY");
        refused(() -> core.store.push(promote, promote.rev));
        final Change faculty = edited("users", facultyA.id, "active", 0L);
        refused(() -> core.store.push(faculty, faculty.rev));
        // What a Core may do: manage an Asoo Core account and a fixture.
        Change asoo = edited("users", userId(DevData.ASOO_EMAIL), "name", "Renamed Asoo");
        accepted(core, asoo);
        Change match = edited("matches", liveMatch(a).id, "venue", "Ground 5");
        accepted(core, match);
    }

    @Test
    public void revisionsCannotBeInventedAndRowsCannotBeDeleted() throws Exception {
        Match live = liveMatch(a);
        String path = "festivals/main/pub/matches:" + live.id;
        // Even Faculty cannot store a row with a revision of their own choosing.
        try {
            Tasks.await(a.firestore.document(path).update("rev", 999999L));
            fail("A row was stored with an invented revision");
        } catch (Exception expected) {
            // correct
        }
        try {
            Tasks.await(a.firestore.document(path).delete());
            fail("A row was deleted outright");
        } catch (Exception expected) {
            // correct
        }
        // The counter cannot be moved on its own to jump ahead.
        try {
            Tasks.await(a.firestore.document("festivals/main").update("rev", 999999L));
            fail("The revision counter was changed directly");
        } catch (Exception expected) {
            // correct
        }
        assertNotNull(a.store.get("matches", live.id));
    }

    @Test
    public void anAccountSignedInOnASecondDeviceStopsTheFirstOneWriting() throws Exception {
        final Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        final Match live = liveMatch(a);
        Change first = edited("matches", live.id, "venue", "Ground 6");
        accepted(core, first);

        // The same account signs in on another device: the session moves there.
        Map<String, Object> session = new HashMap<>();
        session.put("dev", 424242L);
        Tasks.await(core.firestore.document("festivals/main/sessions/" + core.uid).set(session));

        final Change second = edited("matches", live.id, "venue", "Ground 7");
        refused(() -> core.store.push(second, second.rev));
        // A deactivated account is refused as well.
        Change deactivate = edited("users", userId(DevData.ASOO_EMAIL), "active", 0L);
        a.store.push(deactivate, deactivate.rev);
        final Device asoo = device("device-asoo", false);
        signUp(asoo, DevData.ASOO_EMAIL);
        link(asoo, userId(DevData.ASOO_EMAIL), Role.ASOO_CORE, null);
        final Change byDeactivated = edited("matches", live.id, "score_a", 8L);
        refused(() -> asoo.store.push(byDeactivated, byDeactivated.rev));
        refused(() -> asoo.store.changesSince(0));
    }

    @Test
    public void onlyOneDeviceCanUploadAFestivalToTheServer() throws Exception {
        Device second = device("device-b", true);
        signUp(second, "second@flavium.test");
        second.scope.role = Role.FACULTY;
        try {
            second.sync.uploadAsFestival();
            fail("A second festival was uploaded");
        } catch (RuntimeException expected) {
            // refused by the engine, and the server would refuse the rows too
        }
        assertNull("Nothing of the second device is on the server",
                a.store.get("sports", second.repo.getSports().get(0).id));
        assertEquals(0, second.sync.pendingCount());
    }

    // =====================================================================
    // Writing to the server directly, the way a modified app could
    // =====================================================================

    /** A row document as the adapter writes it. The revision is filled in when it is sent. */
    private Map<String, Object> document(Device d, Change change) {
        Map<String, Object> document = new HashMap<>();
        document.put("t", change.table);
        document.put("k", change.key);
        document.put("rev", 0L);
        document.put("del", change.deleted);
        document.put("v", new HashMap<>(change.values));
        document.put("br", change.branch);
        document.put("sp", change.sport);
        document.put("dev", d.sync.deviceId());
        document.put("by", d.uid);
        return document;
    }

    /**
     * Writes documents exactly as given, in one transaction, without the adapter. The
     * counter moves by one and names "last" (null: the counter is left alone). Paths
     * are below festivals/main and alternate with their documents.
     */
    @SuppressWarnings("unchecked")
    private Task<Void> rawWrite(final Device d, final String last, final boolean lastPriv,
                                final Object... pathsAndDocuments) {
        final DocumentReference meta = d.firestore.document("festivals/main");
        return d.firestore.runTransaction(transaction -> {
            long rev = transaction.get(meta).getLong("rev") + 1;
            if (last != null) {
                transaction.update(meta, "rev", rev, "last", last, "lastPriv", lastPriv);
            }
            for (int i = 0; i < pathsAndDocuments.length; i += 2) {
                Map<String, Object> document =
                        new HashMap<>((Map<String, Object>) pathsAndDocuments[i + 1]);
                document.put("rev", rev);
                transaction.set(d.firestore.document("festivals/main/" + pathsAndDocuments[i]),
                        document);
            }
            return null;
        });
    }

    /** The server must refuse this, and for the right reason: its rules, not a mistake here. */
    private void denied(String what, Task<?> task) throws Exception {
        try {
            Tasks.await(task);
            fail("The server allowed: " + what);
        } catch (ExecutionException e) {
            assertTrue(what + ": " + e.getCause(), e.getCause() instanceof FirebaseFirestoreException);
            assertEquals(what, FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    ((FirebaseFirestoreException) e.getCause()).getCode());
        }
    }

    private DocumentSnapshot read(String path) throws Exception {
        return Tasks.await(a.firestore.document("festivals/main/" + path).get());
    }

    /** The first value the question finds on device A, or null when it finds nothing. */
    private String one(String sql, String... args) {
        try {
            return DatabaseUtils.stringForQuery(a.helper.getReadableDatabase(), sql, args);
        } catch (android.database.sqlite.SQLiteDoneException nothing) {
            return null;
        }
    }

    /** A Coach of one sport, on a device of their own that asks for that sport's rows. */
    private Device joinAsCoach(String sportId) throws Exception {
        String coachId = userId("coach@flavium.test");
        if (coachId == null || coachId.isEmpty()) {
            throw new IllegalStateException("Create the coach account first");
        }
        Device coach = device("device-coach", false);
        signUp(coach, "coach@flavium.test");
        link(coach, coachId, Role.COACH, null);
        coach.scope.sportIds = new ArrayList<>(Arrays.asList(sportId));
        coach.sync.joinFestival();
        return coach;
    }

    // ----- Public copies -----

    @Test
    public void thePublicCopyOfARegistrationCannotBeForgedOrLeftOut() throws Exception {
        User crOnA = a.repo.getUser(userId(DevData.CAPTAIN_EMAIL));
        Device cr = join("device-cr", DevData.CAPTAIN_EMAIL, Role.CAPTAIN, crOnA.branchId);
        String pendingId = one("SELECT id FROM registrations WHERE branch_id = ? "
                + "AND status = 'PENDING'", crOnA.branchId);
        String id = "registrations:" + pendingId;

        // The honest pair, written by hand, is accepted: so the refusals below are
        // about the content and not about how this test writes.
        Change honest = edited("registrations", pendingId, "contact_name", "Honest");
        Tasks.await(rawWrite(cr, id, true, "priv/" + id, document(cr, honest),
                "pub/" + id, document(cr, PublicView.of(honest))));

        // While it waits, the public learns nothing about it.
        DocumentSnapshot hidden = read("pub/" + id);
        assertEquals(Boolean.TRUE, hidden.getBoolean("del"));
        assertTrue(((Map<?, ?>) hidden.get("v")).isEmpty());
        assertEquals("", hidden.getString("br"));
        assertEquals("", hidden.getString("sp"));

        // A Branch CR announcing their waiting team to the public as approved.
        Change again = edited("registrations", pendingId, "contact_name", "Again");
        Change fake = new Change("registrations", pendingId, false,
                new LinkedHashMap<>(again.values));
        fake.values.put("status", "APPROVED");
        fake.values.put("contact_phone", "");
        fake.branch = again.branch;
        fake.sport = again.sport;
        denied("a public copy that says APPROVED", rawWrite(cr, id, true,
                "priv/" + id, document(cr, again), "pub/" + id, document(cr, fake)));
        // The complete row without its public copy, and the public copy on its own.
        denied("a registration without its public copy", rawWrite(cr, id, true,
                "priv/" + id, document(cr, again)));
        denied("a public copy on its own", rawWrite(cr, id, false,
                "pub/" + id, document(cr, fake)));
        Change named = new Change("registrations", pendingId, true, null);
        named.branch = again.branch;
        denied("a hidden copy that still names the branch", rawWrite(cr, id, true,
                "priv/" + id, document(cr, again), "pub/" + id, document(cr, named)));

        // Not even Faculty can publish a phone number or a different team.
        String approvedId = one("SELECT id FROM registrations WHERE status = 'APPROVED' "
                + "AND contact_phone <> '' LIMIT 1");
        String approved = "registrations:" + approvedId;
        Change team = edited("registrations", approvedId, "contact_name", "Named");
        denied("a public copy with the phone number", rawWrite(a, approved, true,
                "priv/" + approved, document(a, team), "pub/" + approved, document(a, team)));
        Change other = PublicView.of(team);
        other.values.put("members", "Somebody Else");
        denied("a public copy that differs from the registration", rawWrite(a, approved, true,
                "priv/" + approved, document(a, team), "pub/" + approved, document(a, other)));
        Tasks.await(rawWrite(a, approved, true, "priv/" + approved, document(a, team),
                "pub/" + approved, document(a, PublicView.of(team))));
        assertEquals("", read("pub/" + approved).get("v.contact_phone"));
        assertEquals("Named", read("pub/" + approved).get("v.contact_name"));
    }

    @Test
    public void anAccountNeverCarriesAPasswordAndItsPublicCopyNeverTheEmail() throws Exception {
        String asooId = userId(DevData.ASOO_EMAIL);
        String id = "users:" + asooId;
        Change honest = edited("users", asooId, "name", "Renamed Asoo");
        Tasks.await(rawWrite(a, id, true, "priv/" + id, document(a, honest),
                "pub/" + id, document(a, PublicView.of(honest))));
        assertEquals(asooId + "@hidden.invalid", read("pub/" + id).get("v.email"));

        for (String secret : new String[]{"password_hash", "salt", "failed_logins"}) {
            Change withSecret = edited("users", asooId, secret, "x");
            denied("an account with " + secret, rawWrite(a, id, true,
                    "priv/" + id, document(a, withSecret),
                    "pub/" + id, document(a, PublicView.of(withSecret))));
        }
        Change renamed = edited("users", asooId, "name", "Again");
        denied("a public account with the real email", rawWrite(a, id, true,
                "priv/" + id, document(a, renamed), "pub/" + id, document(a, renamed)));
        Change lie = PublicView.of(renamed);
        lie.values.put("role", Role.FACULTY);
        denied("a public account with another role", rawWrite(a, id, true,
                "priv/" + id, document(a, renamed), "pub/" + id, document(a, lie)));
        Change noRole = edited("users", asooId, "role", "SUPERUSER");
        denied("an account with an invented role", rawWrite(a, id, true,
                "priv/" + id, document(a, noRole), "pub/" + id,
                document(a, PublicView.of(noRole))));
        // Nothing of all that is on the server.
        assertEquals(Role.ASOO_CORE, read("priv/" + id).get("v.role"));
        assertNull(read("priv/" + id).get("v.password_hash"));
    }

    // ----- Rows, keys and the counter -----

    @Test
    public void aRowCannotPretendToBeAnotherRowAndTheCounterMovesOnlyWithARow() throws Exception {
        Match live = liveMatch(a);
        String id = "matches:" + live.id;
        String otherId = one("SELECT id FROM matches WHERE id <> ? LIMIT 1", live.id);

        Change honest = edited("matches", live.id, "venue", "Ground 8");
        Tasks.await(rawWrite(a, id, false, "pub/" + id, document(a, honest)));

        // The columns name a different fixture than the document does.
        Change wrongKey = edited("matches", live.id, "id", otherId);
        denied("a row whose columns name another row", rawWrite(a, id, false,
                "pub/" + id, document(a, wrongKey)));
        // Extra fields, a wrong writer, a table in the wrong place.
        Map<String, Object> extra = document(a, edited("matches", live.id, "venue", "X"));
        extra.put("admin", true);
        denied("a row with an extra field", rawWrite(a, id, false, "pub/" + id, extra));
        Map<String, Object> wrongWriter = document(a, edited("matches", live.id, "venue", "X"));
        wrongWriter.put("by", "somebody-else");
        denied("a row signed by somebody else", rawWrite(a, id, false, "pub/" + id, wrongWriter));
        Map<String, Object> history = document(a, edited("matches", live.id, "venue", "X"));
        history.put("t", "score_audit");
        denied("a private table in the public place", rawWrite(a, "score_audit:" + live.id,
                false, "pub/score_audit:" + live.id, history));
        // A sport of its own choosing.
        Map<String, Object> wrongSport = document(a, edited("matches", live.id, "venue", "X"));
        wrongSport.put("sp", singles.sportId);
        denied("a fixture filed under another sport", rawWrite(a, id, false,
                "pub/" + id, wrongSport));

        // The counter on its own, or naming a row that is not written with it.
        denied("the counter without a row", rawWrite(a, id, false));
        denied("the counter naming a row that does not exist", rawWrite(a, "matches:nothing",
                false));
        denied("a row without the counter", rawWrite(a, null, false,
                "pub/" + id, document(a, edited("matches", live.id, "venue", "X"))));
        denied("the festival handed to somebody else", a.firestore.document("festivals/main")
                .update("ownerUid", "somebody-else"));
        denied("the festival made unready again", a.firestore.document("festivals/main")
                .update("ready", false));
        assertEquals("Ground 8", a.store.get("matches", live.id).values.get("venue"));
    }

    @Test
    public void aRemovedRowKeepsNothingButItsMarkerAndEveryDeviceHearsOfIt() throws Exception {
        Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        Match live = liveMatch(a);
        String id = "matches:" + live.id;
        long histories = count(a, "score_audit", "match_id = '" + live.id + "'");

        // A "removed" marker that still carries the content, or changes whose row it was.
        Map<String, Object> keeps = document(a, edited("matches", live.id, "venue", "X"));
        keeps.put("del", true);
        denied("a removal that keeps the content", rawWrite(a, id, false, "pub/" + id, keeps));
        Map<String, Object> moved = document(a, new Change("matches", live.id, true, null));
        moved.put("sp", singles.sportId);
        denied("a removal that changes the sport", rawWrite(a, id, false, "pub/" + id, moved));
        denied("a removal of a row that never existed", rawWrite(a, "matches:nothing", false,
                "pub/matches:nothing", document(a, new Change("matches", "nothing", true, null))));

        // The real thing: Faculty removes the fixture in the app.
        a.repo.deleteFixture(facultyA, live.id);
        assertTrue(a.sync.sync());
        assertTrue(String.valueOf(a.sync.takeNotices()), a.sync.takeNotices().isEmpty());
        assertEquals(0, a.sync.pendingCount());
        DocumentSnapshot marker = read("pub/" + id);
        assertEquals(Boolean.TRUE, marker.getBoolean("del"));
        assertTrue(((Map<?, ?>) marker.get("v")).isEmpty());
        assertEquals(sevens.sportId, marker.getString("sp"));

        core.sync.pull();
        assertEquals(0, count(core, "matches", "id = '" + live.id + "'"));
        if (histories > 0) {
            assertEquals(0, count(core, "score_audit", "match_id = '" + live.id + "'"));
        }
        Device visitor = device("device-public", false);
        visitor.sync.joinFestival();
        assertEquals(0, count(visitor, "matches", "id = '" + live.id + "'"));
        assertEquals(count(a, "matches", null), count(visitor, "matches", null));

        // Something added and removed again before it was ever sent is nobody's business.
        ContentValues branch = new ContentValues();
        branch.put("id", "temporary-branch");
        branch.put("name", "Temporary");
        a.helper.getWritableDatabase().insertOrThrow("branches", null, branch);
        a.helper.getWritableDatabase().delete("branches", "id = 'temporary-branch'", null);
        assertTrue(a.sync.sync());
        assertTrue(a.sync.takeNotices().isEmpty());
        assertEquals(0, a.sync.pendingCount());
        assertNull(a.store.get("branches", "temporary-branch"));
    }

    // ----- Accounts, links and sessions -----

    @Test
    public void nobodyCanGiveThemselvesOrOthersARoleThroughTheLinks() throws Exception {
        Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        String coreId = userId(DevData.CORE_EMAIL);
        String asooId = userId(DevData.ASOO_EMAIL);
        Map<String, Object> toAsoo = new HashMap<>();
        toAsoo.put("userId", asooId);
        Map<String, Object> toCore = new HashMap<>();
        toCore.put("userId", coreId);
        Map<String, Object> toFaculty = new HashMap<>();
        toFaculty.put("userId", facultyA.id);

        // A Core re-pointing Faculty's sign-in (which would lock Faculty out), or their own.
        denied("a Core changing Faculty's link", core.firestore
                .document("festivals/main/links/" + a.uid).set(toAsoo));
        denied("a Core linking themselves to Faculty", core.firestore
                .document("festivals/main/links/" + core.uid).set(toFaculty));
        denied("a Core changing their own link", core.firestore
                .document("festivals/main/links/" + core.uid).set(toAsoo));

        // A new sign-in: a Core may make it an Asoo Core, nothing higher.
        Device fresh = device("device-stranger", false);
        signUp(fresh, "fresh@flavium.test");
        denied("a session before being an account", fresh.firestore
                .document("festivals/main/sessions/" + fresh.uid)
                .set(new HashMap<String, Object>() {
                    {
                        put("dev", 5L);
                    }
                }));
        denied("a Core making a Core", core.firestore
                .document("festivals/main/links/" + fresh.uid).set(toCore));
        denied("a Core making a Faculty", core.firestore
                .document("festivals/main/links/" + fresh.uid).set(toFaculty));
        denied("a new sign-in linking itself", fresh.firestore
                .document("festivals/main/links/" + fresh.uid).set(toAsoo));
        Map<String, Object> padded = new HashMap<>(toAsoo);
        padded.put("role", Role.FACULTY);
        denied("a link with extra fields", a.firestore
                .document("festivals/main/links/" + fresh.uid).set(padded));
        Map<String, Object> toNobody = new HashMap<>();
        toNobody.put("userId", "no-such-account");
        denied("a link to an account that does not exist", a.firestore
                .document("festivals/main/links/" + fresh.uid).set(toNobody));
        Tasks.await(core.firestore.document("festivals/main/links/" + fresh.uid).set(toAsoo));

        // Once linked, the Core cannot move it up either.
        denied("a Core promoting a link", core.firestore
                .document("festivals/main/links/" + fresh.uid).set(toCore));
        // And a link is not removed by anyone but Faculty.
        denied("a Core removing a link", core.firestore
                .document("festivals/main/links/" + fresh.uid).delete());

        // The session: only one's own, only a device number.
        fresh.scope.role = Role.ASOO_CORE;
        openSession(fresh);
        denied("somebody else's session", core.firestore
                .document("festivals/main/sessions/" + fresh.uid)
                .set(new HashMap<String, Object>() {
                    {
                        put("dev", 5L);
                    }
                }));
        denied("reading somebody else's session", core.firestore
                .document("festivals/main/sessions/" + fresh.uid).get());
        denied("a session that is not a device number", fresh.firestore
                .document("festivals/main/sessions/" + fresh.uid)
                .set(new HashMap<String, Object>() {
                    {
                        put("dev", "every device");
                    }
                }));

        // The new Asoo Core is exactly that: no accounts, no settings.
        final Change account = edited("users", asooId, "role", Role.FACULTY);
        refused(() -> fresh.store.push(account, account.rev));
        // A Core cannot bring back or take over a removed account, or touch a Coach.
        String coachId = a.repo.createUser(facultyA, "Football Coach", "coach@flavium.test",
                PASSWORD, Role.COACH, null);
        assertTrue(a.sync.sync());
        final Change coach = edited("users", coachId, "name", "Mine");
        refused(() -> core.store.push(coach, coach.rev));
        final Change demote = edited("users", coachId, "role", Role.ASOO_CORE);
        refused(() -> core.store.push(demote, demote.rev));
    }

    // ----- Score history -----

    @Test
    public void aScoreHistoryCanBeAddedToButNotChangedForgedOrRemoved() throws Exception {
        final Device asoo = join("device-asoo", DevData.ASOO_EMAIL, Role.ASOO_CORE, null);
        String asooId = userId(DevData.ASOO_EMAIL);
        Match assigned = liveMatch(a);
        a.repo.updateScore(facultyA, assigned.id, assigned.version, 1, 0, "", Match.STATUS_LIVE);
        assertTrue(a.sync.sync());
        String lineId = one("SELECT id FROM score_audit WHERE match_id = ? LIMIT 1", assigned.id);

        // Changing a line: not the scorer, and not Faculty either.
        final Change tamper = edited("score_audit", lineId, "new_score", "9 - 9");
        refused(() -> asoo.store.push(tamper, tamper.rev));
        final Change tamperByFaculty = edited("score_audit", lineId, "new_score", "9 - 9");
        refused(() -> a.store.push(tamperByFaculty, tamperByFaculty.rev));
        // Removing a line: not the scorer.
        final long rev = a.store.get("score_audit", lineId).rev;
        refused(() -> asoo.store.push(new Change("score_audit", lineId, true, null), rev));

        // A new line in somebody else's name.
        Map<String, Object> values = new LinkedHashMap<>(a.store.get("score_audit", lineId).values);
        String forgedId = UUID.randomUUID().toString();
        values.put("id", forgedId);
        values.put("changed_by", facultyA.id);
        final Change forged = new Change("score_audit", forgedId, false, values);
        forged.sport = sevens.sportId;
        refused(() -> asoo.store.push(forged, 0));
        // A line for a fixture that is not theirs.
        String notMine = one("SELECT id FROM matches WHERE assigned_to IS NULL LIMIT 1");
        Map<String, Object> elsewhere = new LinkedHashMap<>(values);
        String elsewhereId = UUID.randomUUID().toString();
        elsewhere.put("id", elsewhereId);
        elsewhere.put("changed_by", asooId);
        elsewhere.put("match_id", notMine);
        final Change wrongMatch = new Change("score_audit", elsewhereId, false, elsewhere);
        wrongMatch.sport = a.store.get("matches", notMine).sport;
        refused(() -> asoo.store.push(wrongMatch, 0));

        // Their own line for their own fixture is what the app writes.
        Map<String, Object> mine = new LinkedHashMap<>(values);
        String mineId = UUID.randomUUID().toString();
        mine.put("id", mineId);
        mine.put("changed_by", asooId);
        Change own = new Change("score_audit", mineId, false, mine);
        own.sport = sevens.sportId;
        assertTrue(asoo.store.push(own, 0) > 0);

        // The administrative log: nobody removes or rewrites a line, Faculty included.
        String logId = one("SELECT id FROM admin_audit LIMIT 1");
        assertNotNull("The sample data has an administrative log", logId);
        final long logRev = a.store.get("admin_audit", logId).rev;
        refused(() -> a.store.push(new Change("admin_audit", logId, true, null), logRev));
        final Change rewrite = edited("admin_audit", logId, "detail", "nothing happened");
        refused(() -> a.store.push(rewrite, rewrite.rev));
        assertFalse("9 - 9".equals(a.store.get("score_audit", lineId).values.get("new_score")));
    }

    @Test
    public void anAsooCoreChangesTheScoreAndNothingElseAboutAFixture() throws Exception {
        final Device asoo = join("device-asoo", DevData.ASOO_EMAIL, Role.ASOO_CORE, null);
        Match assigned = liveMatch(a);

        final Change venue = edited("matches", assigned.id, "venue", "My garden");
        refused(() -> asoo.store.push(venue, venue.rev));
        final Change date = edited("matches", assigned.id, "match_date", "2030-01-01");
        refused(() -> asoo.store.push(date, date.rev));
        final Change link = edited("matches", assigned.id, "external_url", "https://example.org");
        refused(() -> asoo.store.push(link, link.rev));
        // Removing the fixture, or a set of it.
        final long rev = a.store.get("matches", assigned.id).rev;
        refused(() -> asoo.store.push(new Change("matches", assigned.id, true, null), rev));

        // Taking a set away from somebody else's fixture by moving it to their own.
        String otherSet = one("SELECT p.id FROM match_periods p WHERE p.match_id <> ? LIMIT 1",
                assigned.id);
        if (otherSet != null) {
            final Change move = edited("match_periods", otherSet, "match_id", assigned.id);
            move.sport = sevens.sportId;
            refused(() -> asoo.store.push(move, move.rev));
            final long setRev = a.store.get("match_periods", otherSet).rev;
            refused(() -> asoo.store.push(new Change("match_periods", otherSet, true, null),
                    setRev));
        }

        // The score, the note and the status are theirs.
        Change score = edited("matches", assigned.id, "score_a", 2L, "score_b", 1L,
                "score_note", "after extra time", "version", (long) assigned.version + 1);
        accepted(asoo, score);
    }

    // ----- Official results and the Core -----

    @Test
    public void aCoreLeavesOfficialResultsAloneUnlessFacultyAllowsIt() throws Exception {
        final Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        Match live = liveMatch(a);
        String open = one("SELECT id FROM matches WHERE is_final = 0 AND id <> ? LIMIT 1", live.id);

        // Faculty makes one result official and withdraws the permission.
        accepted(a, edited("matches", live.id, "is_final", 1L));
        accepted(a, edited("settings", "core_can_finalise", "value", 0L));

        final Change correct = edited("matches", live.id, "score_a", 9L);
        refused(() -> core.store.push(correct, correct.rev));
        final Change reopen = edited("matches", live.id, "is_final", 0L);
        refused(() -> core.store.push(reopen, reopen.rev));
        final Change finalise = edited("matches", open, "is_final", 1L);
        refused(() -> core.store.push(finalise, finalise.rev));
        // The sets of an official result are part of that result.
        String set = one("SELECT id FROM match_periods LIMIT 1");
        assertNotNull("The sample data has a match played in sets", set);
        String setMatch = one("SELECT match_id FROM match_periods WHERE id = ?", set);
        accepted(core, edited("match_periods", set, "score_a", 20L));
        accepted(a, edited("matches", setMatch, "is_final", 1L));
        final Change setScore = edited("match_periods", set, "score_a", 30L);
        refused(() -> core.store.push(setScore, setScore.rev));
        // What stays possible: the venue of the official one, anything of an open one.
        accepted(core, edited("matches", live.id, "venue", "Ground 9"));
        accepted(core, edited("matches", open, "score_a", 1L, "score_b", 1L));

        // With the permission back, a Core corrects and finalises.
        accepted(a, edited("settings", "core_can_finalise", "value", 1L));
        accepted(core, edited("matches", live.id, "score_a", 9L));
        accepted(core, edited("matches", open, "is_final", 1L));
    }

    // ----- A Coach reads the assigned sports only -----

    @Test
    public void aCoachReceivesPrivateRowsOfTheirOwnSportsOnly() throws Exception {
        String football = sevens.sportId;
        String other = singles.sportId;
        String coachId = a.repo.createUser(facultyA, "Football Coach", "coach@flavium.test",
                PASSWORD, Role.COACH, null);
        a.repo.setCoachSports(facultyA, coachId, Arrays.asList(football));
        assertTrue(a.sync.sync());
        final Device coach = joinAsCoach(football);

        String inFootball = "category_id IN (SELECT id FROM categories WHERE sport_id = '"
                + football + "')";
        String inOther = "category_id IN (SELECT id FROM categories WHERE sport_id = '"
                + other + "')";
        String secret = "(status <> 'APPROVED' OR contact_phone <> '')";
        assertTrue("The sample data has private registrations in both sports",
                count(a, "registrations", inFootball + " AND " + secret) > 0
                        && count(a, "registrations", inOther + " AND " + secret) > 0);
        assertEquals(count(a, "registrations", inFootball),
                count(coach, "registrations", inFootball));
        assertEquals(count(a, "registrations", inFootball + " AND " + secret),
                count(coach, "registrations", inFootball + " AND " + secret));
        assertEquals("Of other sports only what the public sees", 0,
                count(coach, "registrations", "NOT " + inFootball + " AND " + secret));
        String ofFootball = "match_id IN (SELECT m.id FROM matches m JOIN categories c "
                + "ON c.id = m.category_id WHERE c.sport_id = '" + football + "')";
        assertEquals(count(a, "score_audit", ofFootball), count(coach, "score_audit", ofFootball));
        assertEquals(0, count(coach, "score_audit", "NOT " + ofFootball));
        assertEquals(0, count(coach, "users", "email LIKE '%@flavium.test'"));

        // Asking for more than that is refused, however the question is put.
        coach.scope.sportIds = new ArrayList<>(Arrays.asList(football, other));
        refused(() -> coach.store.changesSince(0));
        coach.scope.sportIds = new ArrayList<>(Arrays.asList(football));
        coach.scope.role = Role.ASOO_CORE;
        refused(() -> coach.store.changesSince(0));
        coach.scope.role = Role.COACH;
        String otherPrivate = one("SELECT id FROM registrations WHERE " + inOther
                + " AND " + secret + " LIMIT 1");
        refused(() -> coach.store.get("registrations", otherPrivate));
        assertNotNull(coach.store.get("registrations", one("SELECT id FROM registrations WHERE "
                + inFootball + " AND " + secret + " LIMIT 1")));

        // When Faculty assigns the other sport too, its rows can be asked for.
        a.repo.setCoachSports(facultyA, coachId, Arrays.asList(football, other));
        assertTrue(a.sync.sync());
        coach.scope.sportIds = new ArrayList<>(Arrays.asList(football, other));
        coach.sync.reloadAll();
        assertEquals(count(a, "registrations", inOther + " AND " + secret),
                count(coach, "registrations", inOther + " AND " + secret));
        // And when it is taken away again, they cannot.
        a.repo.setCoachSports(facultyA, coachId, Arrays.asList(football));
        assertTrue(a.sync.sync());
        refused(() -> coach.store.changesSince(0));
    }

    // =====================================================================
    // Continuous synchronisation: offline, reconnecting, restarts
    // =====================================================================

    /**
     * Every shared row of a table as text, in key order, without the columns that never
     * leave a device. Two devices that share the festival must give the same text.
     */
    private String dump(Device d, String table) {
        StringBuilder text = new StringBuilder();
        String order = "settings".equals(table) ? "name"
                : "coach_sports".equals(table) ? "user_id, sport_id" : "id";
        android.database.Cursor c = d.helper.getReadableDatabase().rawQuery(
                "SELECT * FROM " + table + " ORDER BY " + order, null);
        try {
            while (c.moveToNext()) {
                for (int i = 0; i < c.getColumnCount(); i++) {
                    String column = c.getColumnName(i);
                    if (com.example.flaviumarena.sync.SyncSchema.isLocalOnly(table, column)) {
                        continue;
                    }
                    text.append(column).append('=').append(c.isNull(i) ? "null" : c.getString(i))
                            .append(';');
                }
                text.append('\n');
            }
        } finally {
            c.close();
        }
        return text.toString();
    }

    /** The same festival on both devices, table by table. */
    private void assertSameFestival(Device x, Device y, String... except) {
        List<String> skip = Arrays.asList(except);
        for (String table : com.example.flaviumarena.sync.SyncSchema.TABLES) {
            if (!skip.contains(table)) {
                assertEquals("Table " + table, dump(x, table), dump(y, table));
            }
        }
    }

    /** A local row as the engine would send it. */
    private Change localChange(Device d, String table, String key) {
        Map<String, Object> values = new LinkedHashMap<>();
        android.database.Cursor c = d.helper.getReadableDatabase().rawQuery(
                "SELECT * FROM " + table + " WHERE id = ?", new String[]{key});
        try {
            assertTrue(c.moveToFirst());
            for (int i = 0; i < c.getColumnCount(); i++) {
                switch (c.getType(i)) {
                    case android.database.Cursor.FIELD_TYPE_NULL:
                        values.put(c.getColumnName(i), null);
                        break;
                    case android.database.Cursor.FIELD_TYPE_INTEGER:
                        values.put(c.getColumnName(i), c.getLong(i));
                        break;
                    default:
                        values.put(c.getColumnName(i), c.getString(i));
                }
            }
        } finally {
            c.close();
        }
        Change change = new Change(table, key, false, values);
        Change server = d.store.get(table, key);
        change.branch = server.branch;
        change.sport = server.sport;
        return change;
    }

    @Test
    public void bothDevicesWorkOfflineAndEverythingConvergesWhenTheyReconnect() throws Exception {
        Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        User coreUser = localUser(core, userId(DevData.CORE_EMAIL), Role.CORE);
        Match live = liveMatch(a);
        String pendingId = one("SELECT id FROM registrations WHERE status = 'PENDING' LIMIT 1");
        String teamA = one("SELECT id FROM registrations WHERE category_id = ? "
                + "AND status = 'APPROVED' ORDER BY id LIMIT 1", sevens.id);
        String teamB = one("SELECT id FROM registrations WHERE category_id = ? "
                + "AND status = 'APPROVED' AND id <> ? ORDER BY id LIMIT 1", sevens.id, teamA);

        // Both lose the connection.
        Tasks.await(a.firestore.disableNetwork());
        Tasks.await(core.firestore.disableNetwork());

        // Device A changes a score; device B edits a registration and adds a fixture.
        a.repo.updateScore(facultyA, live.id, live.version, 3, 1, "", Match.STATUS_LIVE);
        Registration edited = core.repo.getRegistration(coreUser, pendingId);
        edited.contactName = "Changed while offline";
        core.repo.saveRegistration(coreUser, edited);
        Match fixture = new Match();
        fixture.categoryId = sevens.id;
        fixture.teamAId = teamA;
        fixture.teamBId = teamB;
        fixture.date = "2026-12-01";
        fixture.time = "09:00";
        fixture.venue = "Offline Ground";
        fixture.round = "Friendly";
        String fixtureId = core.repo.saveFixture(coreUser, fixture);

        // Nothing is lost and nothing is sent.
        assertFalse(a.sync.sync());
        assertFalse(core.sync.sync());
        assertTrue(a.sync.pendingCount() > 0);
        assertTrue(core.sync.pendingCount() > 0);
        assertEquals(Integer.valueOf(3), a.repo.getMatch(live.id).scoreA);
        assertEquals("Offline Ground", core.repo.getMatch(fixtureId).venue);
        assertNull("Device A has not heard of it", a.repo.getMatch(fixtureId));

        // The connection returns: the queues go out by themselves on the next exchange.
        Tasks.await(a.firestore.enableNetwork());
        Tasks.await(core.firestore.enableNetwork());
        assertTrue(a.sync.sync());
        assertTrue(core.sync.sync());
        assertTrue(a.sync.sync());
        assertEquals(0, a.sync.pendingCount());
        assertEquals(0, core.sync.pendingCount());
        assertTrue(String.valueOf(a.sync.takeNotices()), a.sync.takeNotices().isEmpty());
        assertTrue(String.valueOf(core.sync.takeNotices()), core.sync.takeNotices().isEmpty());

        // Each device has the other's work, and nothing twice.
        assertEquals("Offline Ground", a.repo.getMatch(fixtureId).venue);
        assertEquals("Changed while offline",
                a.repo.getRegistration(facultyA, pendingId).contactName);
        assertEquals(Integer.valueOf(3), core.repo.getMatch(live.id).scoreA);
        assertEquals(1, count(a, "matches", "venue = 'Offline Ground'"));
        // A Core may not read the administrative log; everything else is identical.
        assertSameFestival(a, core, "admin_audit");
        assertEquals(a.repo.getStandings(sevens.id).get(0).name,
                core.repo.getStandings(sevens.id).get(0).name);
    }

    @Test
    public void aLostAcknowledgementNeverWritesAChangeTwice() throws Exception {
        String id = one("SELECT id FROM registrations WHERE status = 'PENDING' LIMIT 1");
        Registration r = a.repo.getRegistration(facultyA, id);
        r.contactName = "Sent once";
        a.repo.saveRegistration(facultyA, r);
        assertEquals(1, a.sync.pendingCount());

        // The change reaches the server, but the answer never reaches the device.
        Change sent = localChange(a, "registrations", id);
        long serverRev = a.store.push(sent, a.store.get("registrations", id).rev);

        // The device tries again: the server already has exactly this, so nothing is
        // written a second time and nobody is told about a conflict.
        assertTrue(a.sync.sync());
        assertEquals(0, a.sync.pendingCount());
        assertTrue(String.valueOf(a.sync.takeNotices()), a.sync.takeNotices().isEmpty());
        assertEquals(serverRev, a.store.get("registrations", id).rev);
        assertEquals("Sent once", a.store.get("registrations", id).values.get("contact_name"));
    }

    @Test
    public void changesWaitingWhenTheAppIsClosedAreSentAfterItIsOpenedAgain() throws Exception {
        String name = "restart-test.db";
        context.deleteDatabase(name);
        // A device whose database is a file, so it survives being closed.
        Device core = device("device-b", false);
        core.helper.close();
        core.helper = new DbHelper(context, name, false);
        core.repo = new SqliteArenaRepository(core.helper);
        core.store = new FirestoreRemoteStore(core.firestore,
                new SyncEngine(core.helper, null).deviceId());
        core.sync = new SyncEngine(core.helper, core.store);
        try {
            signUp(core, DevData.CORE_EMAIL);
            link(core, userId(DevData.CORE_EMAIL), Role.CORE, null);
            core.sync.joinFestival();
            User coreUser = localUser(core, userId(DevData.CORE_EMAIL), Role.CORE);

            // Offline, a change is made, and the app is closed.
            Tasks.await(core.firestore.disableNetwork());
            Match live = liveMatch(core);
            core.repo.updateScore(coreUser, live.id, live.version, 7, 7, "", Match.STATUS_LIVE);
            assertFalse(core.sync.sync());
            long device = core.sync.deviceId();
            core.helper.close();

            // Opened again: the change is still waiting, and goes out when it can.
            core.helper = new DbHelper(context, name, false);
            core.repo = new SqliteArenaRepository(core.helper);
            core.sync = new SyncEngine(core.helper, core.store);
            assertEquals("Same installation", device, core.sync.deviceId());
            assertTrue(core.sync.pendingCount() > 0);
            Tasks.await(core.firestore.enableNetwork());
            assertTrue(core.sync.sync());
            assertEquals(0, core.sync.pendingCount());
            a.sync.pull();
            assertEquals(Integer.valueOf(7), a.repo.getMatch(live.id).scoreA);
            assertEquals(a.repo.getScoreAudit(facultyA, live.id).size(),
                    core.repo.getScoreAudit(coreUser, live.id).size());
        } finally {
            Tasks.await(core.firestore.enableNetwork());
            core.helper.close();
            context.deleteDatabase(name);
        }
    }

    @Test
    public void throwsScoredByTheAsooCoreReachFacultyAndTheMedalsAgree() throws Exception {
        final Device asoo = join("device-asoo", DevData.ASOO_EMAIL, Role.ASOO_CORE, null);
        String asooId = userId(DevData.ASOO_EMAIL);
        User asooUser = localUser(asoo, asooId, Role.ASOO_CORE);

        // Faculty sets up a shot put final on device A and assigns it to the Asoo Core.
        SportTemplate template = a.repo.getTemplate("athletics");
        String sportId = null;
        for (Sport s : a.repo.getSports()) {
            if (s.name.equals(template.name)) {
                sportId = s.id;
            }
        }
        if (sportId == null) {
            sportId = a.repo.addSportFromTemplate(facultyA, "athletics", null, null);
        }
        RuleSet rules = template.defaults();
        rules.put(RuleSet.ENGINE, SportTemplate.ENGINE_MEASURED);
        rules.put("event_kind", "Throw");
        rules.put("attempts", "2");
        rules.put("finalists", "1");
        rules.put("extra_attempts", "0");
        Category shot = new Category();
        shot.sportId = sportId;
        shot.name = "Shot Put Sync";
        shot.deadline = "";
        shot.rules = rules.toText();
        String categoryId = a.repo.saveCategory(facultyA, shot);
        List<com.example.flaviumarena.model.Branch> branches = a.repo.getBranches();
        String[] athletes = new String[2];
        for (int i = 0; i < 2; i++) {
            Registration r = new Registration();
            r.categoryId = categoryId;
            r.branchId = branches.get(i).id;
            r.year = 1;
            r.name = "Thrower " + i;
            r.contactName = "Contact";
            r.members = "";
            athletes[i] = a.repo.saveRegistration(facultyA, r);
            a.repo.setRegistrationStatus(facultyA, athletes[i], Registration.STATUS_APPROVED);
        }
        EventRound round = new EventRound();
        round.categoryId = categoryId;
        round.stage = EventRound.STAGE_FINAL;
        round.heatNumber = 0;
        round.date = DateUtil.today();
        round.time = "10:00";
        round.venue = "Throwing circle";
        round.assignedTo = asooId;
        String roundId = a.repo.saveRound(facultyA, round);
        String x = a.repo.addEntry(facultyA, roundId, athletes[0], 0);
        String y = a.repo.addEntry(facultyA, roundId, athletes[1], 0);
        assertTrue(a.sync.sync());

        // The Asoo Core scores it on their own device.
        asoo.sync.pull();
        asoo.repo.setRoundStatus(asooUser, roundId, asoo.repo.getRound(roundId).version,
                Match.STATUS_LIVE);
        int[][] throwsCm = {{1210, 1300}, {1250, 0}};
        String[] entries = {x, y};
        for (int i = 0; i < 2; i++) {
            for (int n = 0; n < 2; n++) {
                int cm = throwsCm[i][n];
                asoo.repo.saveAttempt(asooUser, entries[i], asoo.repo.getRound(roundId).version,
                        n + 1, cm == 0 ? null : cm, cm == 0);
            }
        }
        asoo.repo.setRoundStatus(asooUser, roundId, asoo.repo.getRound(roundId).version,
                Match.STATUS_COMPLETED);
        assertTrue(asoo.sync.sync());
        assertTrue(String.valueOf(asoo.sync.takeNotices()), asoo.sync.takeNotices().isEmpty());
        assertEquals(0, asoo.sync.pendingCount());

        // Faculty sees every attempt and makes the result official.
        a.sync.pull();
        assertEquals(dump(asoo, "event_attempts"), dump(a, "event_attempts"));
        assertEquals(dump(asoo, "event_entries"), dump(a, "event_entries"));
        a.repo.finaliseRound(facultyA, roundId, a.repo.getRound(roundId).version);
        assertTrue(a.sync.sync());
        asoo.sync.pull();
        assertTrue(asoo.repo.getRound(roundId).isFinal);

        // Medals are worked out on each device from the same results, and agree.
        List<com.example.flaviumarena.model.MedalRow> onA = a.repo.getMedalTable();
        List<com.example.flaviumarena.model.MedalRow> onAsoo = asoo.repo.getMedalTable();
        assertFalse(onA.isEmpty());
        assertEquals(onA.size(), onAsoo.size());
        for (int i = 0; i < onA.size(); i++) {
            assertEquals(onA.get(i).branchId, onAsoo.get(i).branchId);
            assertEquals(onA.get(i).gold, onAsoo.get(i).gold);
            assertEquals(onA.get(i).silver, onAsoo.get(i).silver);
            assertEquals(onA.get(i).bronze, onAsoo.get(i).bronze);
        }
        // The official result cannot be changed by the Asoo Core any more, even directly.
        final Change late = edited("event_attempts", one("SELECT id FROM event_attempts "
                + "WHERE entry_id = ? LIMIT 1", x), "distance_cm", 2000L);
        refused(() -> asoo.store.push(late, late.rev));
    }

    // =====================================================================
    // Every device connected at once, changes flowing both ways by themselves
    // =====================================================================

    /** Applies whatever the listener delivers, the way the app does, and counts it. */
    private ListenerRegistration live(final Device d, final CountDownLatch... arrivals) {
        return d.store.listen(d.sync.cursor(), new FirestoreRemoteStore.ChangeSink() {
            @Override
            public void onChanges(List<Change> changes) {
                synchronized (d) {
                    d.sync.applyRemote(changes);
                }
                for (CountDownLatch arrival : arrivals) {
                    arrival.countDown();
                }
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    /** Sends what a device has waiting, never at the same time as it applies changes. */
    private void send(Device d) {
        synchronized (d) {
            d.sync.pushPending();
        }
    }

    /** Waits until a condition holds on a device, as a listener updates it. */
    private void eventually(String what, java.util.concurrent.Callable<Boolean> condition)
            throws Exception {
        long until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("Did not happen within 20 seconds: " + what);
    }

    @Test
    public void aNewInstallationConnectsByItselfAndADeviceWithItsOwnDataIsAskedFirst()
            throws Exception {
        // A phone that was just installed: it becomes part of the festival without anyone
        // pressing anything.
        Device fresh = device("device-public", false);
        assertEquals(SyncEngine.Connection.CONNECTED, fresh.sync.connect(false, null));
        assertEquals(count(a, "matches", null), count(fresh, "matches", null));

        // A phone that holds a festival of its own is left alone until someone decides.
        Device own = device("device-b", true);
        String ownSport = own.repo.getSports().get(0).id;
        assertEquals(SyncEngine.Connection.LOCAL_DATA_IN_THE_WAY, own.sync.connect(false, () -> {
            throw new AssertionError("Must not be replaced");
        }));
        assertNotNull(own.repo.getSport(ownSport));
        assertEquals(SyncEngine.LINK_NONE, own.sync.status().link);
        // Decided: it takes the online festival.
        assertEquals(SyncEngine.Connection.CONNECTED, own.sync.connect(true, null));
        assertEquals(count(a, "sports", null), count(own, "sports", null));
        assertNull("Its own records were replaced, not mixed in", own.repo.getSport(ownSport));
    }

    @Test
    public void twoDevicesWorkingAtTheSameTimeSeeEachOthersChangesWithoutAsking()
            throws Exception {
        final Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        final User coreUser = localUser(core, userId(DevData.CORE_EMAIL), Role.CORE);
        final Match live = liveMatch(a);
        final String pendingId = one("SELECT id FROM registrations WHERE status = 'PENDING' "
                + "LIMIT 1");
        ListenerRegistration onA = live(a);
        ListenerRegistration onB = live(core);
        try {
            // At the same moment: A changes a score, B edits a registration.
            a.repo.updateScore(facultyA, live.id, live.version, 5, 2, "", Match.STATUS_LIVE);
            Registration r = core.repo.getRegistration(coreUser, pendingId);
            r.contactName = "Edited on B";
            core.repo.saveRegistration(coreUser, r);
            Thread sendA = new Thread(() -> send(a));
            Thread sendB = new Thread(() -> send(core));
            sendA.start();
            sendB.start();
            sendA.join();
            sendB.join();

            // Each arrives on the other device through its listener; nobody pulls.
            eventually("A's score on B", () -> {
                Match m = core.repo.getMatch(live.id);
                return m.scoreA != null && m.scoreA == 5;
            });
            eventually("B's edit on A", () -> "Edited on B".equals(
                    a.repo.getRegistration(facultyA, pendingId).contactName));
            assertEquals(0, a.sync.pendingCount());
            assertEquals(0, core.sync.pendingCount());

            // The other way round as well: B creates, A removes, both see both.
            String teamA = one("SELECT id FROM registrations WHERE category_id = ? "
                    + "AND status = 'APPROVED' ORDER BY id LIMIT 1", sevens.id);
            String teamB = one("SELECT id FROM registrations WHERE category_id = ? "
                    + "AND status = 'APPROVED' AND id <> ? ORDER BY id LIMIT 1", sevens.id, teamA);
            Match fixture = new Match();
            fixture.categoryId = sevens.id;
            fixture.teamAId = teamA;
            fixture.teamBId = teamB;
            fixture.date = "2026-12-02";
            fixture.time = "11:00";
            fixture.venue = "Made on B";
            fixture.round = "Friendly";
            final String fixtureId = core.repo.saveFixture(coreUser, fixture);
            send(core);
            eventually("B's new fixture on A", () -> a.repo.getMatch(fixtureId) != null);
            a.repo.deleteFixture(facultyA, fixtureId);
            send(a);
            eventually("A's removal on B", () -> core.repo.getMatch(fixtureId) == null);

            // Both devices now hold exactly the same festival.
            assertSameFestival(a, core, "admin_audit");
        } finally {
            onA.remove();
            onB.remove();
        }
    }

    @Test
    public void aListenerThatLostTheConnectionCatchesUpWhenItReturns() throws Exception {
        final Device core = join("device-b", DevData.CORE_EMAIL, Role.CORE, null);
        final Match live = liveMatch(a);
        ListenerRegistration onB = live(core);
        try {
            Tasks.await(core.firestore.disableNetwork());
            a.repo.updateScore(facultyA, live.id, live.version, 8, 0, "", Match.STATUS_LIVE);
            assertTrue(a.sync.sync());
            Thread.sleep(2000);
            assertFalse("Nothing arrives while B is offline",
                    Integer.valueOf(8).equals(core.repo.getMatch(live.id).scoreA));

            // The connection returns: the same listener delivers what was missed.
            Tasks.await(core.firestore.enableNetwork());
            eventually("the missed score on B", () -> {
                Match m = core.repo.getMatch(live.id);
                return m.scoreA != null && m.scoreA == 8;
            });
            final User coreUser = localUser(core, userId(DevData.CORE_EMAIL), Role.CORE);
            eventually("the missed history line on B", () ->
                    a.repo.getScoreAudit(facultyA, live.id).size()
                            == core.repo.getScoreAudit(coreUser, live.id).size());
        } finally {
            onB.remove();
            Tasks.await(core.firestore.enableNetwork());
        }
    }
}
