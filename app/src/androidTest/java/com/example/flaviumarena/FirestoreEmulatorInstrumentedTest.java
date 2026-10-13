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
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.sync.Change;
import com.example.flaviumarena.sync.RemoteStore;
import com.example.flaviumarena.sync.SyncEngine;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
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
import java.util.concurrent.CountDownLatch;
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
        // Being signed in on this device is what "one session per account" checks.
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
        final Device coach = join("device-coach", "coach@flavium.test", Role.COACH, null);

        // A fixture of their sport.
        Match own = liveMatch(a);
        Change ok = edited("matches", own.id, "venue", "Ground 2");
        accepted(coach, ok);
        // A Coach makes results of their sport official, like Faculty.
        Change official = edited("matches", own.id, "venue", "Ground 3");
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
}
