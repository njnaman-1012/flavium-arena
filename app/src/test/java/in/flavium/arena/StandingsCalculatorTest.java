package in.flavium.arena;

import static org.junit.Assert.assertEquals;

import in.flavium.arena.model.Category;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.StandingRow;
import in.flavium.arena.scoring.StandingsCalculator;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Checks the points-table calculation. Runs on the computer, no phone needed. */
public class StandingsCalculatorTest {

    private static Category league() {
        Category c = new Category();
        c.format = Category.FORMAT_LEAGUE;
        c.ptsWin = 3;
        c.ptsDraw = 1;
        c.ptsLoss = 0;
        return c;
    }

    private static Registration team(String id, String status) {
        Registration r = new Registration();
        r.id = id;
        r.name = id;
        r.status = status;
        return r;
    }

    private static Match match(String a, String b, int scoreA, int scoreB, boolean isFinal) {
        Match m = new Match();
        m.teamAId = a;
        m.teamBId = b;
        m.scoreA = scoreA;
        m.scoreB = scoreB;
        m.isFinal = isFinal;
        return m;
    }

    private final List<Registration> teams = Arrays.asList(
            team("Alpha", Registration.STATUS_APPROVED),
            team("Bravo", Registration.STATUS_APPROVED),
            team("Charlie", Registration.STATUS_APPROVED),
            team("Delta", Registration.STATUS_PENDING));

    @Test
    public void winDrawAndLossGiveTheConfiguredPoints() {
        List<Match> matches = Arrays.asList(
                match("Alpha", "Bravo", 2, 0, true),
                match("Bravo", "Charlie", 1, 1, true));
        List<StandingRow> table = StandingsCalculator.calculate(league(), teams, matches);

        assertEquals(3, table.size()); // the pending team is left out
        assertEquals("Alpha", table.get(0).name);
        assertEquals(3, table.get(0).points);
        assertEquals("Charlie", table.get(1).name); // 1 point, difference 0
        assertEquals(1, table.get(1).points);
        assertEquals("Bravo", table.get(2).name);   // 1 point, difference -2
        assertEquals(1, table.get(2).points);
        assertEquals(2, table.get(2).played);
        assertEquals(1, table.get(2).lost);
        assertEquals(1, table.get(2).drawn);
    }

    @Test
    public void resultsThatAreNotFinalDoNotCount() {
        List<Match> matches = Arrays.asList(match("Alpha", "Bravo", 5, 0, false));
        List<StandingRow> table = StandingsCalculator.calculate(league(), teams, matches);

        for (StandingRow row : table) {
            assertEquals(0, row.played);
            assertEquals(0, row.points);
        }
    }

    @Test
    public void equalPointsAreSeparatedByScoreDifference() {
        List<Match> matches = Arrays.asList(
                match("Alpha", "Charlie", 1, 0, true),
                match("Bravo", "Charlie", 4, 0, true));
        List<StandingRow> table = StandingsCalculator.calculate(league(), teams, matches);

        assertEquals("Bravo", table.get(0).name);
        assertEquals("Alpha", table.get(1).name);
        assertEquals("Charlie", table.get(2).name);
    }
}
