package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import in.flavium.arena.data.ArenaException;
import in.flavium.arena.data.ArenaRepository;
import in.flavium.arena.data.ConflictException;
import in.flavium.arena.data.DbHelper;
import in.flavium.arena.data.DevData;
import in.flavium.arena.data.PermissionDeniedException;
import in.flavium.arena.data.SqliteArenaRepository;
import in.flavium.arena.data.ValidationException;
import in.flavium.arena.model.Branch;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.EventEntry;
import in.flavium.arena.model.EventRound;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.Role;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.SportTemplate;
import in.flavium.arena.model.User;
import in.flavium.arena.util.DateUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Runs a race with heats and a final, and a throwing competition, through the repository
 * the way the event screen does. Uses a temporary in-memory database.
 */
@RunWith(AndroidJUnit4.class)
public class RoundsInstrumentedTest {

    private static final String PASSWORD = "Temp@12345";

    private DbHelper helper;
    private ArenaRepository repo;
    private User faculty, core, asoo;
    private List<Branch> branches;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null, true);
        repo = new SqliteArenaRepository(helper);
        faculty = repo.createFirstFaculty("Test Faculty", "faculty@flavium.test", PASSWORD,
                "faculty@flavium.test");
        core = repo.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE);
        asoo = repo.login(DevData.ASOO_EMAIL, DevData.ASOO_PASSWORD, Role.ASOO_CORE);
        branches = repo.getBranches();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ----- Building blocks -----

    private interface Action {
        void run();
    }

    /** The action must be refused with exactly this kind of error. */
    private void refused(Class<? extends ArenaException> kind, Action action) {
        try {
            action.run();
            fail("Expected " + kind.getSimpleName());
        } catch (ArenaException e) {
            assertTrue("Expected " + kind.getSimpleName() + " but got " + e, kind.isInstance(e));
        }
    }

    /** Adds an athletics category with some rule values changed. */
    private Category category(String name, String... changes) {
        SportTemplate template = repo.getTemplate("athletics");
        String sportId = null;
        for (Sport s : repo.getSports()) {
            if (s.name.equals(template.name)) {
                sportId = s.id;
            }
        }
        if (sportId == null) {
            sportId = repo.addSportFromTemplate(core, "athletics", null, null);
        }
        RuleSet rules = template.defaults();
        for (int i = 0; i < changes.length; i += 2) {
            rules.put(changes[i], changes[i + 1]);
        }
        Category c = new Category();
        c.sportId = sportId;
        c.name = name;
        c.deadline = "";
        c.rules = rules.toText();
        return repo.getCategory(repo.saveCategory(core, c));
    }

    /** Registers and approves an athlete. */
    private String athlete(Category c, int number) {
        Registration r = new Registration();
        r.categoryId = c.id;
        r.branchId = branches.get(number % branches.size()).id;
        r.year = 1 + number % 4;
        r.name = "Athlete " + number;
        r.contactName = "Contact";
        r.members = "";
        String id = repo.saveRegistration(core, r);
        repo.setRegistrationStatus(core, id, Registration.STATUS_APPROVED);
        return id;
    }

    private String round(Category c, String stage, int heatNumber, User scorer) {
        EventRound r = new EventRound();
        r.categoryId = c.id;
        r.stage = stage;
        r.heatNumber = heatNumber;
        r.date = DateUtil.today();
        r.time = "10:00";
        r.venue = "Track";
        r.assignedTo = scorer == null ? null : scorer.id;
        return repo.saveRound(core, r);
    }

    private int version(String roundId) {
        return repo.getRound(roundId).version;
    }

    private void status(User who, String roundId, String status) {
        repo.setRoundStatus(who, roundId, version(roundId), status);
    }

    private void time(User who, String roundId, String entryId, Integer ms) {
        repo.saveTimedResult(who, entryId, version(roundId), ms, EventEntry.STATUS_OK);
    }

    private void attempt(String roundId, String entryId, int number, int cm) {
        repo.saveAttempt(asoo, entryId, version(roundId), number, cm == 0 ? null : cm, cm == 0);
    }

    private EventEntry entry(String roundId, String entryId) {
        for (EventEntry e : repo.getEntries(roundId)) {
            if (e.id.equals(entryId)) {
                return e;
            }
        }
        return null;
    }

    private Category race() {
        return category("100 m Test", RuleSet.ENGINE, SportTemplate.ENGINE_TIMED,
                "event_kind", "Track race", "lanes", "4", "heats", "yes",
                "qualify_per_heat", "1", "fastest_losers", "1");
    }

    // ----- Races -----

    @Test
    public void raceIsRunInHeatsAndTheFinalIsDrawnFromThem() {
        Category c = race();
        assertEquals(SportTemplate.ENGINE_TIMED, c.engine);
        String[] athletes = new String[6];
        for (int i = 0; i < 6; i++) {
            athletes[i] = athlete(c, i);
        }
        String heat1 = round(c, EventRound.STAGE_HEAT, 1, asoo);
        String heat2 = round(c, EventRound.STAGE_HEAT, 2, asoo);
        // The same heat number cannot be used twice.
        refused(ValidationException.class, () -> round(c, EventRound.STAGE_HEAT, 2, asoo));

        // Only Faculty and Core decide who runs; lanes are checked.
        refused(PermissionDeniedException.class, () -> repo.addEntry(asoo, heat1, athletes[0], 1));
        String a = repo.addEntry(core, heat1, athletes[0], 1);
        String b = repo.addEntry(core, heat1, athletes[1], 2);
        String d = repo.addEntry(core, heat1, athletes[2], 3);
        refused(ValidationException.class, () -> repo.addEntry(core, heat1, athletes[0], 4));
        refused(ValidationException.class, () -> repo.addEntry(core, heat1, athletes[3], 2));
        refused(ValidationException.class, () -> repo.addEntry(core, heat1, athletes[3], 5));
        String e = repo.addEntry(core, heat2, athletes[3], 1);
        String f = repo.addEntry(core, heat2, athletes[4], 2);
        String g = repo.addEntry(core, heat2, athletes[5], 3);

        // No results before the heat is live.
        refused(ValidationException.class, () -> time(asoo, heat1, a, 12000));
        status(asoo, heat1, Match.STATUS_LIVE);
        time(asoo, heat1, a, 12500);
        time(asoo, heat1, b, 11900);

        // A change based on an old version is refused and changes nothing.
        int stale = version(heat1) - 1;
        refused(ConflictException.class, () ->
                repo.saveTimedResult(asoo, d, stale, 10000, EventEntry.STATUS_OK));
        assertNull(entry(heat1, d).timeMs);

        assertEquals(Integer.valueOf(1), entry(heat1, b).position);
        assertEquals(Integer.valueOf(2), entry(heat1, a).position);
        assertTrue(entry(heat1, b).qualified);
        assertFalse(entry(heat1, a).qualified);

        // Everyone needs a result, or a reason for not having one, before completion.
        refused(ValidationException.class, () -> status(asoo, heat1, Match.STATUS_COMPLETED));
        repo.setEntryStatus(asoo, d, version(heat1), EventEntry.STATUS_DNS);
        status(asoo, heat1, Match.STATUS_COMPLETED);

        // Making results official is not for an Asoo Core.
        refused(PermissionDeniedException.class, () ->
                repo.finaliseRound(asoo, heat1, version(heat1)));
        repo.finaliseRound(faculty, heat1, version(heat1));
        assertTrue(repo.getRound(heat1).isFinal);
        refused(PermissionDeniedException.class, () -> time(asoo, heat1, a, 11000));
        refused(ValidationException.class, () -> time(core, heat1, a, 11000));

        // The final cannot be drawn while a heat is still open.
        refused(ValidationException.class, () -> repo.createFinalFromHeats(core, c.id));

        status(asoo, heat2, Match.STATUS_LIVE);
        time(asoo, heat2, e, 12100);
        time(asoo, heat2, f, 11500);
        time(asoo, heat2, g, 13000);
        // A disqualified runner keeps the time but loses the place.
        repo.setEntryStatus(asoo, f, version(heat2), EventEntry.STATUS_DQ);
        assertNull(entry(heat2, f).position);
        assertEquals(Integer.valueOf(1), entry(heat2, e).position);
        status(asoo, heat2, Match.STATUS_COMPLETED);
        repo.finaliseRound(faculty, heat2, version(heat2));

        // Winners of both heats, plus the fastest of the rest (12.50 beats 13.00).
        refused(PermissionDeniedException.class, () -> repo.createFinalFromHeats(asoo, c.id));
        String finalId = repo.createFinalFromHeats(core, c.id);
        List<EventEntry> finalists = repo.getEntries(finalId);
        assertEquals(3, finalists.size());
        assertEquals(athletes[1], finalists.get(0).registrationId); // 11.90 in lane 1
        assertEquals(1, finalists.get(0).lane);
        assertEquals(athletes[3], finalists.get(1).registrationId); // 12.10
        assertEquals(athletes[0], finalists.get(2).registrationId); // 12.50
        assertFalse(repo.getRound(finalId).isHeat());
        refused(ValidationException.class, () -> repo.createFinalFromHeats(core, c.id));

        // Nobody qualifies from a final.
        status(asoo, finalId, Match.STATUS_LIVE);
        time(asoo, finalId, finalists.get(0).id, 11800);
        assertFalse(entry(finalId, finalists.get(0).id).qualified);

        // An athlete who is in an event cannot be deleted from the category.
        refused(ValidationException.class, () -> repo.deleteRegistration(core, athletes[0]));
    }

    @Test
    public void officialResultsNeedAReasonToReopenAndEverythingIsRecorded() {
        Category c = category("1500 m Test", RuleSet.ENGINE, SportTemplate.ENGINE_TIMED,
                "event_kind", "Track race", "heats", "no");
        // Without heats there is only a final.
        refused(ValidationException.class, () -> round(c, EventRound.STAGE_HEAT, 1, asoo));
        String id = round(c, EventRound.STAGE_FINAL, 0, asoo);
        refused(ValidationException.class, () -> round(c, EventRound.STAGE_FINAL, 0, asoo));
        String x = repo.addEntry(core, id, athlete(c, 0), 1);
        String y = repo.addEntry(core, id, athlete(c, 1), 2);

        status(asoo, id, Match.STATUS_LIVE);
        time(asoo, id, x, 245000);
        time(asoo, id, y, 245000);
        // Equal times share first place.
        assertEquals(Integer.valueOf(1), entry(id, x).position);
        assertEquals(Integer.valueOf(1), entry(id, y).position);
        // Results cannot be made official before the event is completed.
        refused(ValidationException.class, () -> repo.finaliseRound(faculty, id, version(id)));
        status(asoo, id, Match.STATUS_COMPLETED);
        repo.finaliseRound(faculty, id, version(id));

        refused(ValidationException.class, () ->
                repo.reopenRound(faculty, id, version(id), "  "));
        refused(PermissionDeniedException.class, () ->
                repo.reopenRound(asoo, id, version(id), "Timing error"));
        repo.reopenRound(faculty, id, version(id), "Timing error");
        assertFalse(repo.getRound(id).isFinal);
        time(asoo, id, y, 246000);
        assertEquals(Integer.valueOf(2), entry(id, y).position);

        // Two status changes, three results, finalising and reopening: all in the history,
        // newest first.
        assertEquals(7, repo.getRoundAudit(faculty, id).size());
        assertEquals("Timing error", repo.getRoundAudit(faculty, id).get(1).reason);
        refused(PermissionDeniedException.class, () -> repo.getRoundAudit(null, id));
    }

    @Test
    public void onlyTheAssignedAsooCoreEntersResults() {
        Category c = race();
        String id = round(c, EventRound.STAGE_HEAT, 1, null);
        String x = repo.addEntry(core, id, athlete(c, 0), 1);
        refused(PermissionDeniedException.class, () -> status(asoo, id, Match.STATUS_LIVE));
        status(core, id, Match.STATUS_LIVE);
        refused(PermissionDeniedException.class, () -> time(asoo, id, x, 12000));
        refused(PermissionDeniedException.class, () -> time(null, id, x, 12000));
        time(core, id, x, 12000);
        refused(PermissionDeniedException.class, () -> repo.deleteRound(asoo, id));
        repo.deleteRound(core, id);
        assertNull(repo.getRound(id));
        assertTrue(repo.getRounds(c.id).isEmpty());
    }

    // ----- Throws -----

    @Test
    public void bestValidThrowCountsAndOnlyFinalistsGetExtraAttempts() {
        Category c = category("Shot Put Test", RuleSet.ENGINE, SportTemplate.ENGINE_MEASURED,
                "event_kind", "Throw", "attempts", "2", "finalists", "1", "extra_attempts", "1",
                "tie_break", "Second-best attempt");
        assertEquals(SportTemplate.ENGINE_MEASURED, c.engine);
        // A throwing event has no heats.
        refused(ValidationException.class, () -> round(c, EventRound.STAGE_HEAT, 1, asoo));
        String id = round(c, EventRound.STAGE_FINAL, 0, asoo);
        // The throwing order is given automatically.
        String x = repo.addEntry(core, id, athlete(c, 0), 0);
        String y = repo.addEntry(core, id, athlete(c, 1), 0);
        String z = repo.addEntry(core, id, athlete(c, 2), 0);
        assertEquals(3, entry(id, z).lane);

        status(asoo, id, Match.STATUS_LIVE);
        // Attempts are taken in order.
        refused(ValidationException.class, () -> attempt(id, x, 2, 1000));
        // A valid attempt needs a distance.
        refused(ValidationException.class, () ->
                repo.saveAttempt(asoo, x, version(id), 1, null, false));
        refused(ValidationException.class, () ->
                repo.saveTimedResult(asoo, x, version(id), 12000, EventEntry.STATUS_OK));

        attempt(id, x, 1, 1250);
        attempt(id, x, 2, 1100);
        attempt(id, y, 1, 1250);
        attempt(id, y, 2, 1200);
        attempt(id, z, 1, 0);
        attempt(id, z, 2, 0);

        // Equal best throws: the second-best decides. Only fouls: no position.
        assertEquals(Integer.valueOf(1250), entry(id, x).bestCm);
        assertEquals(Integer.valueOf(1), entry(id, y).position);
        assertEquals(Integer.valueOf(2), entry(id, x).position);
        assertNull(entry(id, z).bestCm);
        assertNull(entry(id, z).position);

        // Only the best one after two attempts throws a third time.
        refused(ValidationException.class, () -> attempt(id, x, 3, 1500));
        attempt(id, y, 3, 1190);
        refused(ValidationException.class, () -> attempt(id, y, 4, 1000));
        // An attempt can be corrected; the positions follow.
        attempt(id, x, 2, 1260);
        assertEquals(Integer.valueOf(1), entry(id, x).position);
        assertEquals(Integer.valueOf(1260), entry(id, x).bestCm);
        assertEquals(2, entry(id, x).attempts.size());

        status(asoo, id, Match.STATUS_COMPLETED);
        repo.finaliseRound(faculty, id, version(id));
        refused(PermissionDeniedException.class, () -> attempt(id, x, 1, 1300));
    }

    @Test
    public void heatsAndFinalsAreOnlyForRacesAndThrows() {
        // The sample football league of the development data uses fixtures.
        Category football = null;
        for (Sport s : repo.getSports()) {
            for (Category c : repo.getCategories(s.id)) {
                if (!SportTemplate.ENGINE_TIMED.equals(c.engine)
                        && !SportTemplate.ENGINE_MEASURED.equals(c.engine)) {
                    football = c;
                }
            }
        }
        assertTrue("The development data has a category with fixtures", football != null);
        Category other = football;
        refused(ValidationException.class, () -> round(other, EventRound.STAGE_FINAL, 0, asoo));
    }
}
