package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.DatabaseUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import in.flavium.arena.data.ArenaException;
import in.flavium.arena.data.ArenaRepository;
import in.flavium.arena.data.DbHelper;
import in.flavium.arena.data.DevData;
import in.flavium.arena.data.SqliteArenaRepository;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.EventRound;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.Role;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.SportTemplate;
import in.flavium.arena.model.StandingRow;
import in.flavium.arena.model.User;
import in.flavium.arena.sync.Change;
import in.flavium.arena.sync.RemoteStore;
import in.flavium.arena.sync.SyncEngine;
import in.flavium.arena.sync.SyncException;
import in.flavium.arena.util.DateUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Two installations and one server. Device A starts with the festival's data and uploads
 * it; device B starts empty and takes the server's data. After that each test changes
 * something on one device and checks that the other one ends up with the same.
 *
 * The two devices are two separate in-memory databases, and the server is FakeRemote.
 * This proves the synchronisation logic; it does not prove anything about a network.
 */
@RunWith(AndroidJUnit4.class)
public class SyncInstrumentedTest {

    private DbHelper helperA, helperB;
    private ArenaRepository repoA, repoB;
    private SyncEngine syncA, syncB;
    private FakeRemote server;
    private User coreA, asooA, coreB;
    private Category sevens;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        server = new FakeRemote();

        helperA = new DbHelper(context, null, true);   // has the development festival
        repoA = new SqliteArenaRepository(helperA);
        syncA = new SyncEngine(helperA, server);
        syncA.uploadAsFestival();

        helperB = new DbHelper(context, null, false);  // a fresh installation
        repoB = new SqliteArenaRepository(helperB);
        syncB = new SyncEngine(helperB, server);
        syncB.joinFestival();

        coreA = repoA.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE);
        asooA = repoA.login(DevData.ASOO_EMAIL, DevData.ASOO_PASSWORD, Role.ASOO_CORE);
        coreB = signedInOnB(DevData.CORE_EMAIL);
        for (Sport s : repoA.getSports()) {
            for (Category c : repoA.getCategories(s.id)) {
                if (c.name.equals("Men's 7-a-side")) {
                    sevens = c;
                }
            }
        }
    }

    @After
    public void tearDown() {
        helperA.close();
        helperB.close();
    }

    /**
     * Device B has no passwords (they never leave a device), so signing in there is the
     * server's job. The test stands in for the server by opening the session directly.
     */
    private User signedInOnB(String email) {
        String id = DatabaseUtils.stringForQuery(helperB.getReadableDatabase(),
                "SELECT id FROM users WHERE email = ?", new String[]{email});
        ContentValues session = new ContentValues();
        session.put("id", "session-" + id);
        session.put("user_id", id);
        session.put("created_at", System.currentTimeMillis());
        session.put("last_seen_at", System.currentTimeMillis());
        helperB.getWritableDatabase().insertOrThrow("sessions", null, session);
        return repoB.resumeSession("session-" + id);
    }

    /** A sends, B receives. */
    private void aToB() {
        syncA.pushPending();
        syncB.pull();
    }

    private void bToA() {
        syncB.pushPending();
        syncA.pull();
    }

    private long count(DbHelper helper, String table) {
        return DatabaseUtils.queryNumEntries(helper.getReadableDatabase(), table);
    }

    private Match liveMatch(ArenaRepository repo) {
        for (Match m : repo.getMatches(sevens.id)) {
            if (Match.STATUS_LIVE.equals(m.status)) {
                return m;
            }
        }
        throw new IllegalStateException("No live match");
    }

    private void score(ArenaRepository repo, User who, String matchId, int a, int b) {
        repo.updateScore(who, matchId, repo.getMatch(matchId).version, a, b, "",
                Match.STATUS_LIVE);
    }

    // ----- Starting up -----

    @Test
    public void aFreshDeviceReceivesTheWholeFestival() {
        for (String table : new String[]{"branches", "users", "sports", "categories",
                "registrations", "matches", "match_periods", "score_audit", "settings"}) {
            assertEquals(table, count(helperA, table), count(helperB, table));
        }
        assertEquals(repoA.getSports().size(), repoB.getSports().size());
        // Standings are worked out on each device from the same results.
        List<StandingRow> a = repoA.getStandings(sevens.id);
        List<StandingRow> b = repoB.getStandings(sevens.id);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).name, b.get(i).name);
            assertEquals(a.get(i).points, b.get(i).points);
        }
        assertEquals(0, syncA.pendingCount());
        assertEquals(0, syncB.pendingCount());
    }

    @Test
    public void passwordsAndSessionsNeverLeaveADevice() {
        for (Change c : server.all()) {
            assertFalse("sessions are not shared", c.table.equals("sessions"));
            assertFalse(c.values.containsKey("password_hash"));
            assertFalse(c.values.containsKey("salt"));
            assertFalse(c.values.containsKey("failed_logins"));
            for (Object value : c.values.values()) {
                assertFalse(String.valueOf(value).startsWith("pbkdf2$"));
            }
        }
        // On device B the accounts exist with their roles but without any password, so
        // a local login is impossible there and has to go through the server.
        assertEquals(0, DatabaseUtils.queryNumEntries(helperB.getReadableDatabase(), "users",
                "password_hash <> ''"));
        try {
            repoB.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE);
            fail("Device B must not accept a password it never had");
        } catch (ArenaException expected) {
            // correct
        }
        assertEquals(Role.CORE, coreB.role);
        // A wrong-password counter on A is not news for the other devices.
        try {
            repoA.login(DevData.CAPTAIN_EMAIL, "wrong-password", Role.CAPTAIN);
        } catch (ArenaException expected) {
            // counted locally
        }
        assertEquals(0, syncA.pendingCount());
    }

    @Test
    public void nothingIsQueuedWhileSynchronisationIsOff() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper alone = new DbHelper(context, null, true);
        ArenaRepository repo = new SqliteArenaRepository(alone);
        SyncEngine engine = new SyncEngine(alone, server);
        assertFalse(engine.isEnabled());
        User core = repo.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE);
        Sport sport = new Sport();
        sport.name = "Kabaddi";
        sport.description = "";
        repo.saveSport(core, sport);
        assertEquals(0, engine.pendingCount());
        alone.close();
    }

    // ----- Who uploads and who joins -----

    private DbHelper device(boolean withFestival) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new DbHelper(context, null, withFestival);
    }

    @Test
    public void onlyOneDeviceCanUploadAndTheOthersJoinWithoutOverwritingIt() {
        // A third device arrives with a festival of its own.
        DbHelper helperC = device(true);
        SyncEngine syncC = new SyncEngine(helperC, server);
        assertTrue(syncC.hasLocalFestival());
        int rowsOnServer = server.all().size();
        try {
            syncC.uploadAsFestival();
            fail("The server already has a festival");
        } catch (SyncException expected) {
            // correct
        }
        assertEquals("Nothing of C reached the server", rowsOnServer, server.all().size());
        assertEquals(SyncEngine.LINK_NONE, syncC.status().link);
        assertEquals(0, syncC.pendingCount());

        // Joining replaces C's own festival with the shared one.
        syncC.joinFestival();
        assertEquals(SyncEngine.LINK_CONNECTED, syncC.status().link);
        for (String table : new String[]{"sports", "categories", "registrations", "matches"}) {
            assertEquals(table, count(helperA, table), count(helperC, table));
        }
        Match live = liveMatch(repoA);
        assertNotNull("C has A's records, with A's ids",
                new SqliteArenaRepository(helperC).getMatch(live.id));
        assertEquals(rowsOnServer, server.all().size());
        // A device that is connected can neither upload nor join again.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (attempt == 0) {
                    syncC.uploadAsFestival();
                } else {
                    syncC.joinFestival();
                }
                fail("Already connected");
            } catch (SyncException expected) {
                // correct
            }
        }
        helperC.close();
    }

    @Test
    public void aNewInstallationConnectsByItselfAndAnotherDevicesDataIsNeverReplacedUnasked() {
        // Nothing online yet: a device just keeps working on its own.
        FakeRemote nothingOnline = new FakeRemote();
        DbHelper fresh = device(false);
        SyncEngine freshSync = new SyncEngine(fresh, nothingOnline);
        assertEquals(SyncEngine.Connection.NOT_SET_UP, freshSync.connect(false, null));
        assertEquals(SyncEngine.LINK_NONE, freshSync.status().link);

        // A new installation: it simply becomes part of the online festival.
        DbHelper helperC = device(false);
        SyncEngine syncC = new SyncEngine(helperC, server);
        assertFalse(syncC.hasLocalFestival());
        final boolean[] asked = {false};
        assertEquals(SyncEngine.Connection.CONNECTED, syncC.connect(false, () -> asked[0] = true));
        assertTrue(asked[0]);
        assertEquals(SyncEngine.LINK_CONNECTED, syncC.status().link);
        assertEquals(count(helperA, "matches"), count(helperC, "matches"));
        // Connecting again changes nothing.
        assertEquals(SyncEngine.Connection.CONNECTED, syncC.connect(false, () -> {
            throw new AssertionError("Not joined twice");
        }));

        // A device with a festival of its own is not touched until someone decides.
        DbHelper helperD = device(true);
        SyncEngine syncD = new SyncEngine(helperD, server);
        // Its own festival: the same sample data as A's, but with ids of its own.
        Match ownMatch = new SqliteArenaRepository(helperD).getMatch(DatabaseUtils.stringForQuery(
                helperD.getReadableDatabase(), "SELECT id FROM matches LIMIT 1", null));
        assertNull("Not A's record", repoA.getMatch(ownMatch.id));
        assertEquals(SyncEngine.Connection.LOCAL_DATA_IN_THE_WAY, syncD.connect(false, () -> {
            throw new AssertionError("Must not replace");
        }));
        assertEquals(SyncEngine.LINK_NONE, syncD.status().link);
        assertNotNull(new SqliteArenaRepository(helperD).getMatch(ownMatch.id));
        // If keeping a copy fails, nothing is replaced.
        try {
            syncD.connect(true, () -> {
                throw new SyncException("no space for the copy");
            });
            fail("The copy failed");
        } catch (SyncException expected) {
            // correct
        }
        assertNotNull(new SqliteArenaRepository(helperD).getMatch(ownMatch.id));
        // Decided: the online festival replaces it.
        assertEquals(SyncEngine.Connection.CONNECTED, syncD.connect(true, null));
        assertNull(new SqliteArenaRepository(helperD).getMatch(ownMatch.id));
        assertEquals(count(helperA, "matches"), count(helperD, "matches"));

        // From now on every device works on the same festival, in both directions.
        Match live = liveMatch(repoA);
        score(repoA, coreA, live.id, 6, 1);
        syncA.sync();
        syncC.sync();
        syncD.sync();
        assertEquals(Integer.valueOf(6), new SqliteArenaRepository(helperC).getMatch(live.id).scoreA);
        assertEquals(Integer.valueOf(6), new SqliteArenaRepository(helperD).getMatch(live.id).scoreA);

        // The device that set up online sync reports itself as unfinished until it is.
        FakeRemote setUp = new FakeRemote();
        setUp.dropAfter = 3;
        DbHelper uploader = device(true);
        SyncEngine uploaderSync = new SyncEngine(uploader, setUp);
        try {
            uploaderSync.uploadAsFestival();
            fail("The connection was lost");
        } catch (RemoteStore.Unavailable expected) {
            // correct
        }
        assertEquals(SyncEngine.Connection.SETTING_UP, uploaderSync.connect(false, null));
        fresh.close();
        helperC.close();
        helperD.close();
        uploader.close();
    }

    @Test
    public void anInterruptedUploadContinuesWithoutSendingAnythingTwice() {
        FakeRemote empty = new FakeRemote();
        DbHelper first = device(true);
        DbHelper second = device(true);
        SyncEngine uploader = new SyncEngine(first, empty);
        SyncEngine other = new SyncEngine(second, empty);

        // Nobody can join a server that has no festival yet.
        try {
            other.joinFestival();
            fail("Nothing to join yet");
        } catch (SyncException expected) {
            // correct
        }

        empty.dropAfter = 12;                      // the connection is lost part-way
        try {
            uploader.uploadAsFestival();
            fail("The connection dropped");
        } catch (RemoteStore.Unavailable expected) {
            // correct
        }
        assertEquals(SyncEngine.LINK_UPLOADING, uploader.status().link);
        assertEquals(12, empty.accepted);
        assertTrue(uploader.pendingCount() > 0);

        // While the first device is half-way, the second can neither upload nor join.
        empty.dropAfter = -1;
        try {
            other.uploadAsFestival();
            fail("Another device is uploading");
        } catch (SyncException expected) {
            // correct
        }
        try {
            other.joinFestival();
            fail("The festival is not complete yet");
        } catch (SyncException expected) {
            // correct
        }
        assertEquals(12, empty.accepted);

        // The first device continues. Every row arrives exactly once.
        uploader.uploadAsFestival();
        assertEquals(SyncEngine.LINK_CONNECTED, uploader.status().link);
        assertEquals(0, uploader.pendingCount());
        assertEquals("One accepted change per row", empty.all().size(), empty.accepted);
        long rows = 0;
        for (String table : in.flavium.arena.sync.SyncSchema.TABLES) {
            rows += count(first, table);
        }
        assertEquals(rows, empty.all().size());

        other.joinFestival();
        assertEquals(count(first, "matches"), count(second, "matches"));
        first.close();
        second.close();
    }

    @Test
    public void statusAndListenerReportWhatHappened() {
        final java.util.Set<String> heard = new java.util.HashSet<>();
        syncB.setListener(new SyncEngine.Listener() {
            @Override
            public void onRemoteChanges(java.util.Set<String> tables) {
                heard.addAll(tables);
            }
        });
        Match live = liveMatch(repoA);
        score(repoA, asooA, live.id, 6, 2);
        assertTrue(syncA.status().pending > 0);
        assertTrue(syncA.sync());
        assertEquals(0, syncA.status().pending);
        assertTrue(syncA.status().lastSync > 0);
        assertEquals("", syncA.status().lastError);

        assertTrue(syncB.sync());
        assertTrue("The screen is told which tables changed", heard.contains("matches"));
        assertTrue(heard.contains("score_audit"));
        // Receiving a change is not a local edit: nothing is queued to go back.
        assertEquals(0, syncB.status().pending);
        heard.clear();
        assertTrue(syncB.sync());
        assertTrue("Nothing new, nothing announced", heard.isEmpty());

        // Without a connection the exchange reports why and keeps the change.
        server.offline = true;
        score(repoA, asooA, live.id, 7, 2);
        assertFalse(syncA.sync());
        assertFalse(syncA.status().lastError.isEmpty());
        assertTrue(syncA.status().pending > 0);
        server.offline = false;
        assertTrue(syncA.sync());
        assertEquals("", syncA.status().lastError);
    }

    @Test
    public void theLocalDatabaseCanBeCopiedBeforeJoining() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "sync_backup_test.db";
        context.deleteDatabase(name);
        DbHelper onDisk = new DbHelper(context, name, true);
        long matches = count(onDisk, "matches");
        java.io.File copy = in.flavium.arena.sync.LocalBackup.copy(context, onDisk);
        try {
            assertTrue(copy.length() > 0);
            // The copy is a complete database of its own.
            android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase
                    .openDatabase(copy.getPath(), null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            assertEquals(matches, DatabaseUtils.queryNumEntries(db, "matches"));
            db.close();
            // Joining afterwards replaces the device's data; the copy keeps the old data.
            new SyncEngine(onDisk, server).joinFestival();
        } finally {
            onDisk.close();
            context.deleteDatabase(name);
            copy.delete();
        }
        // A temporary database has no file, and saying so is better than pretending.
        try {
            in.flavium.arena.sync.LocalBackup.copy(context, helperA);
            fail("No file to copy");
        } catch (SyncException expected) {
            // correct
        }
    }

    // ----- Changes travel both ways -----

    @Test
    public void aScoreUpdatedOnOneDeviceAppearsOnTheOtherWithItsHistory() {
        Match live = liveMatch(repoA);
        score(repoA, asooA, live.id, 3, 1);
        aToB();
        Match onB = repoB.getMatch(live.id);
        assertEquals(Integer.valueOf(3), onB.scoreA);
        assertEquals(Integer.valueOf(1), onB.scoreB);
        assertEquals(repoA.getMatch(live.id).version, onB.version);
        assertEquals(repoA.getScoreAudit(coreA, live.id).size(),
                repoB.getScoreAudit(coreB, live.id).size());

        // The result is completed and made official on device B; A's table follows.
        repoB.updateScore(coreB, live.id, repoB.getMatch(live.id).version, 3, 1, "",
                Match.STATUS_COMPLETED);
        repoB.finaliseMatch(coreB, live.id, repoB.getMatch(live.id).version);
        bToA();
        assertTrue(repoA.getMatch(live.id).isFinal);
        List<StandingRow> a = repoA.getStandings(sevens.id);
        List<StandingRow> b = repoB.getStandings(sevens.id);
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).name, b.get(i).name);
            assertEquals(a.get(i).points, b.get(i).points);
            assertEquals(a.get(i).played, b.get(i).played);
        }
        assertTrue(syncA.takeNotices().isEmpty());
        assertTrue(syncB.takeNotices().isEmpty());
    }

    @Test
    public void aRegistrationMadeOnOneDeviceAppearsOnTheOther() {
        Registration r = new Registration();
        r.categoryId = sevens.id;
        r.branchId = repoB.getBranches().get(0).id;
        r.year = 1;
        r.contactName = "Contact";
        r.members = "A\nB\nC\nD\nE\nF\nG";
        String id = repoB.saveRegistration(coreB, r);
        bToA();
        Registration onA = repoA.getRegistration(coreA, id);
        assertNotNull(onA);
        assertEquals(Registration.STATUS_PENDING, onA.status);

        repoA.setRegistrationStatus(coreA, id, Registration.STATUS_APPROVED);
        aToB();
        assertEquals(Registration.STATUS_APPROVED, repoB.getRegistration(coreB, id).status);
    }

    @Test
    public void removingAnEventRemovesItAndItsEntrantsEverywhere() {
        SportTemplate template = repoA.getTemplate("athletics");
        String sportId = repoA.addSportFromTemplate(coreA, "athletics", null, null);
        RuleSet rules = template.defaults();
        rules.put(RuleSet.ENGINE, SportTemplate.ENGINE_TIMED);
        rules.put("event_kind", "Track race");
        rules.put("heats", "no");
        Category c = new Category();
        c.sportId = sportId;
        c.name = "Sync Mile";
        c.deadline = "";
        c.rules = rules.toText();
        String categoryId = repoA.saveCategory(coreA, c);
        Registration runner = new Registration();
        runner.categoryId = categoryId;
        runner.branchId = repoA.getBranches().get(0).id;
        runner.year = 2;
        runner.name = "Runner One";
        runner.contactName = "Contact";
        runner.members = "";
        String runnerId = repoA.saveRegistration(coreA, runner);
        repoA.setRegistrationStatus(coreA, runnerId, Registration.STATUS_APPROVED);
        EventRound round = new EventRound();
        round.categoryId = categoryId;
        round.stage = EventRound.STAGE_FINAL;
        round.date = DateUtil.today();
        round.time = "09:00";
        round.venue = "Track";
        String roundId = repoA.saveRound(coreA, round);
        repoA.addEntry(coreA, roundId, runnerId, 1);
        aToB();
        assertEquals(1, repoB.getEntries(roundId).size());
        assertEquals("Runner One", repoB.getEntries(roundId).get(0).name);

        repoA.deleteRound(coreA, roundId);
        aToB();
        assertNull(repoB.getRound(roundId));
        assertEquals(0, count(helperB, "event_entries"));
        assertEquals(count(helperA, "event_rounds"), count(helperB, "event_rounds"));
    }

    // ----- No connection -----

    @Test
    public void changesMadeWithoutAConnectionWaitAndAreSentOnce() {
        Match live = liveMatch(repoA);
        server.offline = true;
        score(repoA, asooA, live.id, 2, 0);
        score(repoA, asooA, live.id, 2, 1);
        try {
            syncA.pushPending();
            fail("The server cannot be reached");
        } catch (RemoteStore.Unavailable expected) {
            // the changes stay queued
        }
        assertTrue(syncA.pendingCount() > 0);
        // The device keeps working on its own data meanwhile.
        assertEquals(Integer.valueOf(1), repoA.getMatch(live.id).scoreB);

        server.offline = false;
        int before = server.accepted;
        aToB();
        assertEquals(Integer.valueOf(2), repoB.getMatch(live.id).scoreA);
        assertEquals(Integer.valueOf(1), repoB.getMatch(live.id).scoreB);
        // The match row was changed twice but travels once, in its final state.
        assertEquals(0, syncA.pendingCount());
        int sent = server.accepted - before;
        assertEquals(0, syncA.pushPending());
        assertEquals("Sending again stores nothing twice", before + sent, server.accepted);
    }

    @Test
    public void anAnswerLostOnTheWayDoesNotCreateAConflict() {
        Match live = liveMatch(repoA);
        score(repoA, asooA, live.id, 4, 4);
        // The change reaches the server, but device A never hears back and still has
        // the row in its queue with the old revision.
        for (Change c : readQueueOf(syncA, helperA)) {
            server.push(c, revisionOnServer(c));
        }
        assertTrue(syncA.pendingCount() > 0);
        syncA.pushPending();
        assertEquals(0, syncA.pendingCount());
        assertTrue("No conflict is reported", syncA.takeNotices().isEmpty());
        syncB.pull();
        assertEquals(Integer.valueOf(4), repoB.getMatch(live.id).scoreB);
    }

    private long revisionOnServer(Change c) {
        Change current = server.get(c.table, c.key);
        return current == null ? 0 : current.rev;
    }

    /** The rows device A is about to send, read the way the engine would. */
    private List<Change> readQueueOf(SyncEngine engine, DbHelper helper) {
        // A second engine on the same database, pointed at a server that records
        // instead of storing, gives exactly what would be sent.
        final List<Change> seen = new java.util.ArrayList<>();
        SyncEngine probe = new SyncEngine(helper, new RemoteStore() {
            @Override
            public long push(Change change, long baseRev) {
                seen.add(change);
                throw new Unavailable("recording only");
            }

            @Override
            public List<Change> changesSince(long revision) {
                return new java.util.ArrayList<>();
            }

            @Override
            public Change get(String table, String key) {
                return null;
            }

            @Override
            public Dataset dataset() {
                return new Dataset(0, false);
            }

            @Override
            public boolean claim(long deviceId) {
                return false;
            }

            @Override
            public void markReady(long deviceId) {
            }
        });
        try {
            probe.pushPending();
        } catch (RemoteStore.Unavailable expected) {
            // it stops at the first row; take them one at a time
        }
        return seen;
    }

    // ----- Two people at once -----

    @Test
    public void whenTwoDevicesChangeTheSameMatchTheFirstWinsAndTheSecondIsTold() {
        Match live = liveMatch(repoA);
        score(repoA, asooA, live.id, 5, 0);                 // on A
        repoB.updateScore(coreB, live.id, repoB.getMatch(live.id).version, 0, 5, "",
                Match.STATUS_LIVE);                           // on B, at the same time
        syncA.pushPending();                                  // A reaches the server first
        syncB.pushPending();                                  // B is refused

        // B now shows what the server has, and says why its own score is gone.
        assertEquals(Integer.valueOf(5), repoB.getMatch(live.id).scoreA);
        assertEquals(Integer.valueOf(0), repoB.getMatch(live.id).scoreB);
        assertFalse(syncB.takeNotices().isEmpty());

        syncA.pull();
        syncB.pull();
        assertEquals(repoA.getMatch(live.id).version, repoB.getMatch(live.id).version);
        assertEquals(Integer.valueOf(5), repoA.getMatch(live.id).scoreA);

        // The history tells one story on both devices and on the server: the score that
        // lost left no line behind.
        List<in.flavium.arena.model.ScoreAudit> historyA =
                repoA.getScoreAudit(coreA, live.id);
        List<in.flavium.arena.model.ScoreAudit> historyB =
                repoB.getScoreAudit(coreB, live.id);
        assertEquals(historyA.size(), historyB.size());
        for (int i = 0; i < historyA.size(); i++) {
            assertEquals(historyA.get(i).id, historyB.get(i).id);
        }
        for (in.flavium.arena.model.ScoreAudit line : historyB) {
            assertFalse("The refused score is not in the history",
                    line.newScore.contains("0 - 5"));
        }
        assertEquals(0, syncB.pendingCount());
        assertEquals(count(helperA, "score_audit"), count(helperB, "score_audit"));
        int historyOnServer = 0;
        for (Change c : server.all()) {
            if (c.table.equals("score_audit") && !c.deleted
                    && live.id.equals(c.values.get("match_id"))) {
                historyOnServer++;
            }
        }
        assertEquals(historyA.size(), historyOnServer);
    }

    @Test
    public void anOldCopyCannotOverwriteNewerServerData() {
        Match live = liveMatch(repoA);
        // B changes the match but is not connected.
        repoB.updateScore(coreB, live.id, repoB.getMatch(live.id).version, 9, 9, "",
                Match.STATUS_LIVE);
        // Meanwhile A changes it twice and both changes reach the server.
        score(repoA, asooA, live.id, 1, 0);
        syncA.pushPending();
        score(repoA, asooA, live.id, 2, 0);
        syncA.pushPending();

        // B comes back and hears from the server before it sends anything.
        syncB.pull();
        assertEquals(Integer.valueOf(2), repoB.getMatch(live.id).scoreA);
        assertFalse(syncB.takeNotices().isEmpty());
        syncB.pushPending();
        syncA.pull();
        assertEquals("A still has its own newer score", Integer.valueOf(2),
                repoA.getMatch(live.id).scoreA);
        assertEquals(Integer.valueOf(0), repoA.getMatch(live.id).scoreB);
    }

    @Test
    public void aChangeTheServerRefusesIsUndoneLocally() {
        server.refusedTables.add("sports");
        Sport sport = new Sport();
        sport.name = "Kabaddi";
        sport.description = "";
        String id = repoB.saveSport(coreB, sport);
        assertNotNull(repoB.getSport(id));
        syncB.pushPending();
        assertNull("The sport the server refused is gone again", repoB.getSport(id));
        assertFalse(syncB.takeNotices().isEmpty());
        assertEquals(0, syncB.pendingCount());
        syncA.pull();
        assertNull(repoA.getSport(id));
    }
}
