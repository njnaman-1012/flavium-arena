package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.ConflictException;
import com.example.flaviumarena.data.DbHelper;
import com.example.flaviumarena.data.DevData;
import com.example.flaviumarena.data.PermissionDeniedException;
import com.example.flaviumarena.data.SqliteArenaRepository;
import com.example.flaviumarena.data.ValidationException;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.MatchPeriod;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.util.DateUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Plays whole matches through the repository, the way the scoring screen does, for each
 * kind of scoring. Uses a temporary in-memory database with the development data.
 */
@RunWith(AndroidJUnit4.class)
public class ScoringInstrumentedTest {

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

    private Sport sport(String name) {
        for (Sport s : repo.getSports()) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** Adds a category from a template, with some rule values changed. */
    private Category category(String templateKey, String name, String... changes) {
        SportTemplate template = repo.getTemplate(templateKey);
        Sport existing = sport(template.name);
        String sportId = existing != null ? existing.id
                : repo.addSportFromTemplate(core, templateKey, null, null);
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

    /** Registers and approves an entry for the branch at the given position. */
    private String entry(Category c, int branchIndex, String personName) {
        Registration r = new Registration();
        r.categoryId = c.id;
        r.branchId = branches.get(branchIndex).id;
        r.year = 2;
        r.name = personName;
        r.contactName = "Contact";
        StringBuilder members = new StringBuilder();
        for (int i = 1; i <= c.minSize; i++) {
            members.append("Member ").append(i).append("\n");
        }
        r.members = members.toString();
        String id = repo.saveRegistration(core, r);
        repo.setRegistrationStatus(core, id, Registration.STATUS_APPROVED);
        return id;
    }

    private String fixture(Category c, String sideA, String sideB) {
        Match m = new Match();
        m.categoryId = c.id;
        m.teamAId = sideA;
        m.teamBId = sideB;
        m.date = DateUtil.today();
        m.time = "10:00";
        m.venue = "Court";
        m.round = "Round 1";
        m.assignedTo = asoo.id;
        return repo.saveFixture(core, m);
    }

    private Match match(String id) {
        return repo.getMatch(id);
    }

    private void status(String id, String status) {
        repo.setMatchStatus(asoo, id, match(id).version, status);
    }

    private void set(String id, int number, int a, int b) {
        repo.saveSetScore(asoo, id, match(id).version, number, a, b, 0, 0);
    }

    // ----- Volleyball: sets with a two-point margin -----

    @Test
    public void volleyballBestOfThreeToFifteenWithDeuce() {
        Category c = category("volleyball", "Men's Short", "sets_best_of", "3", "set_target",
                "15", "deciding_target", "15");
        assertEquals(SportTemplate.ENGINE_SETS, c.engine);
        String id = fixture(c, entry(c, 0, null), entry(c, 1, null));

        // Nothing can be scored before the match is live.
        refused(ValidationException.class, () -> set(id, 1, 1, 0));
        status(id, Match.STATUS_LIVE);

        // 15-14: the target is reached but the set is NOT won.
        set(id, 1, 15, 14);
        assertEquals(Integer.valueOf(0), match(id).scoreA);
        assertEquals(Match.STATUS_LIVE, match(id).status);
        // 16-14 wins it.
        set(id, 1, 16, 14);
        assertEquals(Integer.valueOf(1), match(id).scoreA);
        assertEquals(Integer.valueOf(0), match(id).scoreB);
        // A score past the end of the set is impossible.
        refused(ValidationException.class, () -> set(id, 1, 17, 14));
        // Sets are played in order.
        refused(ValidationException.class, () -> set(id, 3, 1, 0));

        // A paused match cannot be scored; resuming allows it again.
        status(id, Match.STATUS_PAUSED);
        refused(ValidationException.class, () -> set(id, 2, 1, 0));
        status(id, Match.STATUS_LIVE);

        // A match cannot be marked completed while it is undecided.
        refused(ValidationException.class, () -> status(id, Match.STATUS_COMPLETED));

        set(id, 2, 10, 15);
        assertEquals(Integer.valueOf(1), match(id).scoreB);
        set(id, 3, 15, 13);
        // Two sets to one: the match completes by itself.
        Match done = match(id);
        assertEquals(Integer.valueOf(2), done.scoreA);
        assertEquals(Integer.valueOf(1), done.scoreB);
        assertEquals(Match.STATUS_COMPLETED, done.status);
        assertEquals(1, done.winnerSide());
        assertEquals(3, repo.getPeriods(id).size());

        // No fourth set, and no correction that would contradict the later sets.
        refused(ValidationException.class, () -> set(id, 4, 1, 0));
        refused(ValidationException.class, () -> set(id, 2, 15, 10));
        // A correction that stays consistent is accepted and changes the winner.
        set(id, 3, 13, 15);
        assertEquals(2, match(id).winnerSide());
        assertEquals(Match.STATUS_COMPLETED, match(id).status);

        // Every accepted change is in the score history.
        assertTrue(repo.getScoreAudit(core, id).size() >= 8);
    }

    @Test
    public void onlyTheAssignedScorerAndAdminsMayScoreAndFinalResultsAreProtected() {
        Category c = category("volleyball", "Women's Short", "sets_best_of", "1", "set_target",
                "15");
        String id = fixture(c, entry(c, 0, null), entry(c, 1, null));
        String otherAsooId = repo.createUser(faculty, "Other Asoo", "other@flavium.test",
                PASSWORD, Role.ASOO_CORE, null);
        User otherAsoo = repo.getUser(otherAsooId);

        refused(PermissionDeniedException.class, () ->
                repo.setMatchStatus(otherAsoo, id, match(id).version, Match.STATUS_LIVE));
        refused(PermissionDeniedException.class, () ->
                repo.setMatchStatus(null, id, match(id).version, Match.STATUS_LIVE));
        status(id, Match.STATUS_LIVE);
        refused(PermissionDeniedException.class, () ->
                repo.saveSetScore(otherAsoo, id, match(id).version, 1, 1, 0, 0, 0));
        refused(PermissionDeniedException.class, () ->
                repo.saveSetScore(null, id, match(id).version, 1, 1, 0, 0, 0));

        // Two people saving from the same starting point: the second one is refused.
        int stale = match(id).version;
        set(id, 1, 1, 0);
        refused(ConflictException.class, () ->
                repo.saveSetScore(core, id, stale, 1, 0, 1, 0, 0));
        assertEquals(1, repo.getPeriods(id).get(0).scoreA);

        set(id, 1, 15, 3);
        assertEquals(Match.STATUS_COMPLETED, match(id).status);
        refused(PermissionDeniedException.class, () ->
                repo.finaliseMatch(asoo, id, match(id).version));
        repo.finaliseMatch(faculty, id, match(id).version);

        // Final: nobody scores, not even Faculty, until it is reopened with a reason.
        refused(PermissionDeniedException.class, () -> set(id, 1, 15, 4));
        refused(ValidationException.class, () ->
                repo.saveSetScore(faculty, id, match(id).version, 1, 15, 4, 0, 0));
        refused(PermissionDeniedException.class, () ->
                repo.reopenMatch(asoo, id, match(id).version, "typo"));
        refused(ValidationException.class, () ->
                repo.reopenMatch(faculty, id, match(id).version, "  "));
        repo.reopenMatch(faculty, id, match(id).version, "Set score was entered wrongly");
        assertFalse(match(id).isFinal);
        repo.saveSetScore(faculty, id, match(id).version, 1, 15, 4, 0, 0);
        repo.finaliseMatch(faculty, id, match(id).version);
        assertTrue(match(id).isFinal);
        // The reopening and its reason are in the history.
        boolean found = false;
        for (com.example.flaviumarena.model.ScoreAudit a : repo.getScoreAudit(faculty, id)) {
            found |= "REOPENED".equals(a.action) && a.reason.contains("wrongly");
        }
        assertTrue(found);
    }

    // ----- Lawn tennis: points, games, deuce, tie-break -----

    @Test
    public void tennisPointsGamesAndTieBreak() {
        Category c = category("lawn_tennis", "Men's Singles", "participation", "Singles");
        assertEquals(Category.TYPE_INDIVIDUAL, c.type);
        String id = fixture(c, entry(c, 0, "Player A"), entry(c, 1, "Player B"));
        status(id, Match.STATUS_LIVE);

        // Four straight points win a game.
        for (int i = 0; i < 4; i++) {
            repo.scoreTennisPoint(asoo, id, match(id).version, "A");
        }
        MatchPeriod set = repo.getPeriods(id).get(0);
        assertEquals(1, set.scoreA);
        assertEquals(0, set.ptA);

        // Deuce: 40-40, advantage B, deuce again, then two in a row for A.
        for (String side : new String[]{"A", "A", "A", "B", "B", "B", "B", "A"}) {
            repo.scoreTennisPoint(asoo, id, match(id).version, side);
        }
        set = repo.getPeriods(id).get(0);
        assertEquals("No game is won at deuce", 1, set.scoreA + set.scoreB);
        assertEquals(4, set.ptA);
        assertEquals(4, set.ptB);
        repo.scoreTennisPoint(asoo, id, match(id).version, "A");
        repo.scoreTennisPoint(asoo, id, match(id).version, "A");
        set = repo.getPeriods(id).get(0);
        assertEquals(2, set.scoreA);
        assertEquals(0, set.ptA);

        // Jump to 6-6 by correcting the set, then play the tie-break point by point.
        repo.saveSetScore(asoo, id, match(id).version, 1, 6, 6, 0, 0);
        assertEquals(Integer.valueOf(0), match(id).scoreA);
        for (String side : new String[]{"A", "B", "A", "B", "A", "B", "A", "B", "A", "B"}) {
            repo.scoreTennisPoint(asoo, id, match(id).version, side);
        }
        set = repo.getPeriods(id).get(0);
        assertEquals("5-5 in the tie-break", 5, set.tbA);
        assertEquals(6, set.scoreA);
        repo.scoreTennisPoint(asoo, id, match(id).version, "A");   // 6-5: not two clear
        assertEquals(6, repo.getPeriods(id).get(0).scoreA);
        repo.scoreTennisPoint(asoo, id, match(id).version, "A");   // 7-5: set
        set = repo.getPeriods(id).get(0);
        assertEquals(7, set.scoreA);
        assertEquals(6, set.scoreB);
        assertEquals(7, set.tbA);
        assertEquals(5, set.tbB);
        assertEquals(Integer.valueOf(1), match(id).scoreA);

        // A set cannot be typed as 8-6 when a tie-break is in use, nor 7-6 without one.
        refused(ValidationException.class, () ->
                repo.saveSetScore(asoo, id, match(id).version, 2, 8, 6, 0, 0));
        refused(ValidationException.class, () ->
                repo.saveSetScore(asoo, id, match(id).version, 2, 7, 6, 0, 0));
        repo.saveSetScore(asoo, id, match(id).version, 2, 6, 3, 0, 0);
        assertEquals(Match.STATUS_COMPLETED, match(id).status);
        assertEquals(Integer.valueOf(2), match(id).scoreA);
    }

    // ----- Football: one score each, a shoot-out, events -----

    @Test
    public void footballKnockoutNeedsAWinnerAndRecordsEvents() {
        Category c = category("football", "Knockout Cup", "format", "Knockout", "allow_draw",
                "no", "penalty_shootout", "yes");
        assertEquals(SportTemplate.ENGINE_SCORE, c.engine);
        String id = fixture(c, entry(c, 0, null), entry(c, 1, null));

        // A match that has not started cannot have a score or an event.
        refused(ValidationException.class, () -> repo.updateScore(asoo, id, match(id).version,
                1, 0, "", Match.STATUS_UPCOMING));
        refused(ValidationException.class, () ->
                repo.addEvent(asoo, id, 5, "A", "Goal", "Someone"));

        repo.updateScore(asoo, id, match(id).version, 1, 0, "", Match.STATUS_LIVE, 1, null, null);
        assertEquals(1, match(id).currentPeriod);
        repo.addEvent(asoo, id, 12, "A", "Goal", "Arjun");
        String card = repo.addEvent(asoo, id, 30, "B", "Yellow card", "");
        assertEquals(2, repo.getEvents(id).size());           // anyone can read them
        refused(PermissionDeniedException.class, () ->
                repo.addEvent(null, id, 40, "B", "Goal", ""));
        refused(PermissionDeniedException.class, () -> repo.deleteEvent(null, card));
        repo.deleteEvent(asoo, card);
        assertEquals(1, repo.getEvents(id).size());

        // Penalties are only for a level score.
        refused(ValidationException.class, () -> repo.updateScore(asoo, id, match(id).version,
                2, 1, "", Match.STATUS_LIVE, 2, 4, 3));

        repo.updateScore(asoo, id, match(id).version, 1, 1, "", Match.STATUS_COMPLETED, 2,
                null, null);
        assertEquals(0, match(id).winnerSide());
        // A knockout cannot be finalised level without a shoot-out.
        refused(ValidationException.class, () ->
                repo.finaliseMatch(faculty, id, match(id).version));
        repo.updateScore(asoo, id, match(id).version, 1, 1, "", Match.STATUS_COMPLETED, 4, 4, 3);
        assertEquals(1, match(id).winnerSide());
        repo.finaliseMatch(faculty, id, match(id).version);
        assertTrue(match(id).isFinal);
    }

    @Test
    public void setScoringIsRefusedForASportScoredWithOneScore() {
        Category c = category("football", "League Cup");
        String id = fixture(c, entry(c, 0, null), entry(c, 1, null));
        status(id, Match.STATUS_LIVE);
        refused(ValidationException.class, () -> set(id, 1, 1, 0));
        refused(ValidationException.class, () -> repo.saveInnings(asoo, id, match(id).version,
                1, "A", 10, 0, 1, 0, 0));
    }

    // ----- Cricket: innings totals and the CricHeroes link -----

    @Test
    public void cricketInningsAndCricHeroesLink() {
        Category c = repo.getCategories(sport("Cricket").id).get(0);
        assertEquals(SportTemplate.ENGINE_CRICKET, c.engine);
        Match fixture = repo.getMatches(c.id).get(0);
        String id = fixture.id;
        assertEquals("", fixture.externalUrl);

        // Only a secure CricHeroes address is accepted, and only by a Core or Faculty.
        fixture.externalUrl = "https://example.com/scorecard/1";
        refused(ValidationException.class, () -> repo.saveFixture(core, fixture));
        fixture.externalUrl = "http://cricheroes.com/scorecard/1/x";
        refused(ValidationException.class, () -> repo.saveFixture(core, fixture));
        fixture.externalUrl = "https://cricheroes.com/scorecard/1234567/test-match";
        refused(PermissionDeniedException.class, () -> repo.saveFixture(asoo, fixture));
        repo.saveFixture(core, fixture);
        assertEquals("https://cricheroes.com/scorecard/1234567/test-match",
                match(id).externalUrl);

        // The link belongs to cricket only.
        Match football = repo.getMatches(repo.getCategories(sport("Football").id).get(0).id)
                .get(2);
        football.externalUrl = "https://cricheroes.com/scorecard/1234567/test-match";
        refused(ValidationException.class, () -> repo.saveFixture(core, football));

        refused(ValidationException.class, () -> repo.saveInnings(asoo, id, match(id).version,
                1, "A", 100, 5, 10, 0, 4));   // not live yet
        status(id, Match.STATUS_LIVE);
        refused(ValidationException.class, () -> repo.saveInnings(asoo, id, match(id).version,
                1, "A", 100, 5, 11, 0, 4));   // more than the 10 overs of this category
        refused(ValidationException.class, () -> repo.saveInnings(asoo, id, match(id).version,
                1, "A", 100, 11, 10, 0, 4));  // more wickets than a side has
        refused(ValidationException.class, () -> repo.saveInnings(asoo, id, match(id).version,
                1, "A", 100, 5, 9, 6, 4));    // a sixth ball completes the over
        refused(ValidationException.class, () -> repo.saveInnings(asoo, id, match(id).version,
                2, "B", 50, 1, 5, 0, 0));     // second innings before the first

        repo.saveInnings(asoo, id, match(id).version, 1, "A", 120, 6, 10, 0, 8);
        assertEquals(Integer.valueOf(120), match(id).scoreA);
        assertEquals(Integer.valueOf(0), match(id).scoreB);
        refused(ValidationException.class, () -> repo.saveInnings(asoo, id, match(id).version,
                2, "A", 50, 1, 5, 0, 0));     // the same side cannot bat twice in a row
        repo.saveInnings(asoo, id, match(id).version, 2, "B", 121, 4, 9, 3, 5);

        Match done = match(id);
        assertEquals(Integer.valueOf(121), done.scoreB);
        List<MatchPeriod> innings = repo.getPeriods(id);
        assertEquals(2, innings.size());
        assertEquals("9.3", innings.get(1).oversText());
        assertEquals(4, innings.get(1).wickets);
        status(id, Match.STATUS_COMPLETED);
        assertEquals(2, match(id).winnerSide());
    }

    // ----- Chess: win, draw or loss -----

    @Test
    public void chessStoresOnlyAResult() {
        Category c = category("chess", "Open Rapid", "participation", "Individual");
        assertEquals(SportTemplate.ENGINE_RESULT, c.engine);
        String id = fixture(c, entry(c, 0, "Player A"), entry(c, 1, "Player B"));

        refused(ValidationException.class, () -> repo.updateScore(asoo, id, match(id).version,
                3, 1, "", Match.STATUS_COMPLETED));
        repo.updateScore(asoo, id, match(id).version, 1, 1, "", Match.STATUS_COMPLETED);
        assertEquals(0, match(id).winnerSide());
        repo.updateScore(asoo, id, match(id).version, 0, 1, "", Match.STATUS_COMPLETED);
        assertEquals(2, match(id).winnerSide());
        assertNull(match(id).penA);
    }

    // ----- The development sample match -----

    @Test
    public void sampleBadmintonMatchIsConsistent() {
        Category singles = repo.getCategories(sport("Badminton").id).get(0);
        Match live = null;
        for (Match m : repo.getMatches(singles.id)) {
            if (Match.STATUS_LIVE.equals(m.status)) {
                live = m;
            }
        }
        final Match semi = live;
        assertEquals(SportTemplate.ENGINE_SETS, semi.categoryEngine);
        assertEquals(Integer.valueOf(1), semi.scoreA);
        assertEquals(2, repo.getPeriods(semi.id).size());
        // The second game is at 9-11: one more point to B makes it 9-12, still in play.
        repo.saveSetScore(asoo, semi.id, semi.version, 2, 9, 12, 0, 0);
        assertEquals(Match.STATUS_LIVE, match(semi.id).status);
    }
}
