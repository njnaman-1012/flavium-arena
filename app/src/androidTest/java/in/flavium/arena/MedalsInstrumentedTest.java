package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import in.flavium.arena.data.ArenaRepository;
import in.flavium.arena.data.DbHelper;
import in.flavium.arena.data.DevData;
import in.flavium.arena.data.SqliteArenaRepository;
import in.flavium.arena.model.Branch;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.EventEntry;
import in.flavium.arena.model.EventRound;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.MedalRow;
import in.flavium.arena.model.MedalWin;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.Role;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.SportTemplate;
import in.flavium.arena.model.User;
import in.flavium.arena.scoring.Medals;
import in.flavium.arena.util.DateUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Medals worked out from official results, for a league, a knockout and a race, and the
 * medal table of the branches. Uses a temporary in-memory database.
 */
@RunWith(AndroidJUnit4.class)
public class MedalsInstrumentedTest {

    private static final String PASSWORD = DevData.newPassword();

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
        // The medal table starts with no medals at all.
        assertTrue(repo.getMedals(null).isEmpty());
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ----- Building blocks -----

    private Category category(String templateKey, String name, String... changes) {
        SportTemplate template = repo.getTemplate(templateKey);
        String sportId = null;
        for (Sport s : repo.getSports()) {
            if (s.name.equals(template.name)) {
                sportId = s.id;
            }
        }
        if (sportId == null) {
            sportId = repo.addSportFromTemplate(core, templateKey, null, null);
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

    /** Registers and approves the team (or the athlete) of the branch at this position. */
    private String entry(Category c, int branchIndex, String personName) {
        Registration r = new Registration();
        r.categoryId = c.id;
        r.branchId = branches.get(branchIndex).id;
        r.year = 2;
        r.name = personName;
        r.contactName = "Contact";
        StringBuilder members = new StringBuilder();
        for (int i = 1; c.isTeam() && i <= c.minSize; i++) {
            members.append("Member ").append(i).append("\n");
        }
        r.members = members.toString();
        String id = repo.saveRegistration(core, r);
        repo.setRegistrationStatus(core, id, Registration.STATUS_APPROVED);
        return id;
    }

    /** Plays a fixture to a finished score. It is made official separately. */
    private String played(Category c, String round, String sideA, String sideB, int a, int b) {
        Match m = new Match();
        m.categoryId = c.id;
        m.teamAId = sideA;
        m.teamBId = sideB;
        m.date = DateUtil.today();
        m.time = "10:00";
        m.venue = "Ground";
        m.round = round;
        m.assignedTo = asoo.id;
        String id = repo.saveFixture(core, m);
        repo.setMatchStatus(asoo, id, repo.getMatch(id).version, Match.STATUS_LIVE);
        repo.updateScore(asoo, id, repo.getMatch(id).version, a, b, "", Match.STATUS_COMPLETED);
        return id;
    }

    private void official(String matchId) {
        repo.finaliseMatch(faculty, matchId, repo.getMatch(matchId).version);
    }

    private String medalOf(List<MedalWin> wins, String registrationId) {
        for (MedalWin w : wins) {
            if (w.registrationId.equals(registrationId)) {
                return w.medal;
            }
        }
        return null;
    }

    private MedalRow rowOf(int branchIndex) {
        for (MedalRow row : repo.getMedalTable()) {
            if (row.branchId.equals(branches.get(branchIndex).id)) {
                return row;
            }
        }
        return null;
    }

    // ----- League -----

    @Test
    public void leagueMedalsAppearOnlyWhenEveryFixtureIsFinal() {
        Category c = category("football", "Medal League", RuleSet.FORMAT, "League");
        String a = entry(c, 0, null);
        String b = entry(c, 1, null);
        String d = entry(c, 2, null);
        String ab = played(c, "Round 1", a, b, 2, 0);
        String ad = played(c, "Round 2", a, d, 1, 0);
        String bd = played(c, "Round 3", b, d, 3, 1);
        official(ab);
        official(ad);
        // One fixture is still provisional: nobody has a medal yet.
        assertTrue(repo.getMedals(c.id).isEmpty());

        official(bd);
        List<MedalWin> wins = repo.getMedals(c.id);
        assertEquals(3, wins.size());
        assertEquals(Medals.GOLD, medalOf(wins, a));
        assertEquals(Medals.SILVER, medalOf(wins, b));
        assertEquals(Medals.BRONZE, medalOf(wins, d));
        assertEquals(Medals.GOLD, wins.get(0).medal); // gold is listed first
        assertEquals(branches.get(0).id, wins.get(0).branchId);

        // The table counts medals per branch and has no points.
        assertEquals(1, rowOf(0).gold);
        assertEquals(1, rowOf(1).silver);
        assertEquals(1, rowOf(2).bronze);
        assertEquals(1, rowOf(0).rank);
        assertEquals(0, rowOf(3).total());
        assertEquals(branches.get(0).id, repo.getMedalTable().get(0).branchId);

        // Reopening a result takes the medals back until it is final again.
        repo.reopenMatch(faculty, bd, repo.getMatch(bd).version, "Score check");
        assertTrue(repo.getMedals(c.id).isEmpty());
        assertEquals(0, rowOf(0).total());
    }

    // ----- Knockout -----

    @Test
    public void knockoutMedalsComeFromTheFinalAndTheThirdPlaceMatch() {
        Category c = category("football", "Medal Cup", RuleSet.FORMAT, "Knockout");
        assertTrue(c.isKnockout());
        String a = entry(c, 0, null);
        String b = entry(c, 1, null);
        String d = entry(c, 2, null);
        String e = entry(c, 3, null);
        official(played(c, "Semi-final", a, b, 1, 0));
        official(played(c, "Semi-final", d, e, 0, 2));
        // Semi-finals decide no medals.
        assertTrue(repo.getMedals(c.id).isEmpty());

        String finalId = played(c, "Final", a, e, 0, 1);
        assertTrue("A provisional final decides nothing", repo.getMedals(c.id).isEmpty());
        official(finalId);
        List<MedalWin> wins = repo.getMedals(c.id);
        assertEquals(2, wins.size());
        assertEquals(Medals.GOLD, medalOf(wins, e));
        assertEquals(Medals.SILVER, medalOf(wins, a));

        official(played(c, "Third place", b, d, 3, 2));
        wins = repo.getMedals(c.id);
        assertEquals(3, wins.size());
        assertEquals(Medals.BRONZE, medalOf(wins, b));
    }

    @Test
    public void theCategoryRuleLimitsTheMedals() {
        Category silverToo = category("football", "Two Medal Cup", RuleSet.FORMAT, "Knockout",
                "medals", "Gold and Silver");
        String a = entry(silverToo, 0, null);
        String b = entry(silverToo, 1, null);
        official(played(silverToo, "Third place", a, b, 1, 0));
        assertTrue("No bronze in this category", repo.getMedals(silverToo.id).isEmpty());
        official(played(silverToo, "Final", a, b, 1, 0));
        assertEquals(2, repo.getMedals(silverToo.id).size());

        Category none = category("football", "Friendly Cup", RuleSet.FORMAT, "Knockout",
                "medals", "No medals");
        String x = entry(none, 0, null);
        String y = entry(none, 1, null);
        official(played(none, "Final", x, y, 2, 1));
        assertTrue(repo.getMedals(none.id).isEmpty());

        // Across the festival: only the two medals of the first category.
        assertEquals(2, repo.getMedals(null).size());
    }

    // ----- Race -----

    @Test
    public void raceMedalsFollowTheOfficialFinalAndTiesShareAMedal() {
        Category c = category("athletics", "Medal Mile", RuleSet.ENGINE,
                SportTemplate.ENGINE_TIMED, "event_kind", "Track race", "heats", "no");
        EventRound round = new EventRound();
        round.categoryId = c.id;
        round.stage = EventRound.STAGE_FINAL;
        round.date = DateUtil.today();
        round.time = "09:00";
        round.venue = "Track";
        round.assignedTo = asoo.id;
        String id = repo.saveRound(core, round);

        String[] athletes = new String[4];
        String[] entries = new String[4];
        int[] times = {245000, 245000, 250000, 260000};
        for (int i = 0; i < 4; i++) {
            athletes[i] = entry(c, i, "Runner " + i);
            entries[i] = repo.addEntry(core, id, athletes[i], i + 1);
        }
        repo.setRoundStatus(asoo, id, repo.getRound(id).version, Match.STATUS_LIVE);
        for (int i = 0; i < 4; i++) {
            repo.saveTimedResult(asoo, entries[i], repo.getRound(id).version, times[i],
                    EventEntry.STATUS_OK);
        }
        repo.setRoundStatus(asoo, id, repo.getRound(id).version, Match.STATUS_COMPLETED);
        assertTrue("Provisional results decide nothing", repo.getMedals(c.id).isEmpty());

        repo.finaliseRound(faculty, id, repo.getRound(id).version);
        List<MedalWin> wins = repo.getMedals(c.id);
        // Two runners share first place: two golds, no silver, bronze for third.
        assertEquals(3, wins.size());
        assertEquals(Medals.GOLD, medalOf(wins, athletes[0]));
        assertEquals(Medals.GOLD, medalOf(wins, athletes[1]));
        assertEquals(Medals.BRONZE, medalOf(wins, athletes[2]));
        assertEquals(null, medalOf(wins, athletes[3]));
        assertEquals(1, rowOf(0).gold);
        assertEquals(1, rowOf(1).gold);
        assertEquals(1, rowOf(0).rank);
        assertEquals(1, rowOf(1).rank);
        assertEquals(3, rowOf(2).rank);

        repo.reopenRound(faculty, id, repo.getRound(id).version, "Photo finish review");
        assertTrue(repo.getMedals(c.id).isEmpty());
    }
}
