package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import in.flavium.arena.model.EventEntry;
import in.flavium.arena.scoring.EventRanking;
import in.flavium.arena.scoring.Measure;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Times, distances and the ranking of races and throws. Runs on the computer. */
public class EventRankingTest {

    private static EventEntry runner(String name, Integer timeMs, String status) {
        EventEntry e = new EventEntry();
        e.name = name;
        e.timeMs = timeMs;
        e.resultStatus = status;
        return e;
    }

    /** A thrower; a distance of 0 in the list means a foul. */
    private static EventEntry thrower(String name, int... distancesCm) {
        EventEntry e = new EventEntry();
        e.name = name;
        for (int i = 0; i < distancesCm.length; i++) {
            EventEntry.Attempt a = new EventEntry.Attempt();
            a.number = i + 1;
            a.foul = distancesCm[i] == 0;
            a.distanceCm = a.foul ? null : Integer.valueOf(distancesCm[i]);
            e.attempts.add(a);
        }
        return e;
    }

    // ----- Reading and writing values -----

    @Test
    public void timesAreReadInSecondsOrMinutesAndSeconds() {
        assertEquals(12340, Measure.parseTimeMs("12.34"));
        assertEquals(12000, Measure.parseTimeMs("12"));
        assertEquals(12345, Measure.parseTimeMs(" 12,345 "));
        assertEquals(62350, Measure.parseTimeMs("1:02.35"));
        assertEquals(245000, Measure.parseTimeMs("4:05"));
        assertEquals(Measure.INVALID, Measure.parseTimeMs(""));
        assertEquals(Measure.INVALID, Measure.parseTimeMs("fast"));
        assertEquals(Measure.INVALID, Measure.parseTimeMs("0"));
        assertEquals(Measure.INVALID, Measure.parseTimeMs("-3.2"));
        assertEquals(Measure.INVALID, Measure.parseTimeMs("1:75.00"));
        assertEquals(Measure.INVALID, Measure.parseTimeMs(null));
    }

    @Test
    public void timesAreWrittenLikeAStopwatch() {
        assertEquals("12.34", Measure.formatTime(12340));
        assertEquals("9.80", Measure.formatTime(9800));
        assertEquals("1:02.35", Measure.formatTime(62350));
        assertEquals("4:05.00", Measure.formatTime(245000));
    }

    @Test
    public void distancesAreReadAndWrittenInMetres() {
        assertEquals(1245, Measure.parseDistanceCm("12.45"));
        assertEquals(1200, Measure.parseDistanceCm("12"));
        assertEquals(950, Measure.parseDistanceCm("9.5"));
        assertEquals(Measure.INVALID, Measure.parseDistanceCm("12.456"));
        assertEquals(Measure.INVALID, Measure.parseDistanceCm("0"));
        assertEquals(Measure.INVALID, Measure.parseDistanceCm("far"));
        assertEquals("12.45", Measure.formatDistance(1245));
        assertEquals("9.05", Measure.formatDistance(905));
    }

    // ----- Races -----

    @Test
    public void fastestTimeWinsAndOnlyValidResultsArePlaced() {
        EventEntry a = runner("A", 12500, "");
        EventEntry b = runner("B", 11900, "");
        EventEntry c = runner("C", 11000, EventEntry.STATUS_DQ);
        EventEntry d = runner("D", null, "");
        EventEntry e = runner("E", null, EventEntry.STATUS_DNS);
        EventRanking.rankTimed(Arrays.asList(a, b, c, d, e), 1);

        assertEquals(Integer.valueOf(1), b.position);
        assertEquals(Integer.valueOf(2), a.position);
        assertNull("A disqualified runner has no place", c.position);
        assertNull(d.position);
        assertNull(e.position);
        assertTrue(b.qualified);
        assertFalse(a.qualified);
        assertFalse(c.qualified);
    }

    @Test
    public void equalTimesShareAPositionAndTheNextOneIsSkipped() {
        EventEntry a = runner("A", 12000, "");
        EventEntry b = runner("B", 12000, "");
        EventEntry c = runner("C", 12500, "");
        EventRanking.rankTimed(Arrays.asList(c, a, b), 2);
        assertEquals(Integer.valueOf(1), a.position);
        assertEquals(Integer.valueOf(1), b.position);
        assertEquals(Integer.valueOf(3), c.position);
        assertTrue(a.qualified);
        assertTrue(b.qualified);
        assertFalse(c.qualified);
    }

    @Test
    public void aFinalHasNoQualifiers() {
        EventEntry a = runner("A", 12000, "");
        EventRanking.rankTimed(Arrays.asList(a), 0);
        assertEquals(Integer.valueOf(1), a.position);
        assertFalse(a.qualified);
    }

    @Test
    public void fastestLosersAreTakenAcrossHeats() {
        // Heat 1
        EventEntry h1a = runner("H1A", 11000, "");
        EventEntry h1b = runner("H1B", 11800, "");
        EventEntry h1c = runner("H1C", 12900, "");
        // Heat 2
        EventEntry h2a = runner("H2A", 11500, "");
        EventEntry h2b = runner("H2B", 12100, "");
        EventEntry h2c = runner("H2C", 12000, EventEntry.STATUS_DQ);
        EventRanking.rankTimed(Arrays.asList(h1a, h1b, h1c), 1);
        EventRanking.rankTimed(Arrays.asList(h2a, h2b, h2c), 1);

        List<EventEntry> all = Arrays.asList(h1a, h1b, h1c, h2a, h2b, h2c);
        List<EventEntry> extra = EventRanking.fastestNonQualifiers(all, 2);
        assertEquals(2, extra.size());
        assertEquals("H1B", extra.get(0).name);
        assertEquals("H2B", extra.get(1).name);
        // Asking for more than there are returns what exists.
        assertEquals(3, EventRanking.fastestNonQualifiers(all, 10).size());
    }

    // ----- Throws -----

    @Test
    public void bestValidThrowCountsAndFoulsNever() {
        EventEntry a = thrower("A", 1100, 0, 1250);     // best 12.50
        EventEntry b = thrower("B", 0, 0, 0);            // only fouls
        EventEntry c = thrower("C", 1300, 1000);         // best 13.00
        EventEntry d = thrower("D");                     // has not thrown
        EventRanking.rankMeasured(Arrays.asList(a, b, c, d), true);

        assertEquals(Integer.valueOf(1250), a.bestCm);
        assertNull(b.bestCm);
        assertEquals(Integer.valueOf(1), c.position);
        assertEquals(Integer.valueOf(2), a.position);
        assertNull("Only fouls: no position", b.position);
        assertNull(d.position);
    }

    @Test
    public void equalBestThrowsAreSeparatedBySecondBestWhenTheRuleSaysSo() {
        EventEntry a = thrower("A", 1250, 1100);
        EventEntry b = thrower("B", 1250, 1200);
        List<EventEntry> both = Arrays.asList(a, b);

        EventRanking.rankMeasured(both, true);
        assertEquals(Integer.valueOf(1), b.position);
        assertEquals(Integer.valueOf(2), a.position);

        EventRanking.rankMeasured(both, false);
        assertEquals("Without the rule they share first place", Integer.valueOf(1), a.position);
        assertEquals(Integer.valueOf(1), b.position);
    }

    @Test
    public void aDisqualifiedThrowerKeepsTheMarkButLosesThePlace() {
        EventEntry a = thrower("A", 1400);
        a.resultStatus = EventEntry.STATUS_DQ;
        EventEntry b = thrower("B", 1000);
        EventRanking.rankMeasured(Arrays.asList(a, b), true);
        assertNull(a.position);
        assertEquals(Integer.valueOf(1400), a.bestCm);
        assertEquals(Integer.valueOf(1), b.position);
    }

    @Test
    public void onlyTheBestAfterTheFirstRoundsGetExtraAttempts() {
        EventEntry a = thrower("A", 1000, 1100, 1200);
        EventEntry b = thrower("B", 900, 950, 990);
        EventEntry c = thrower("C", 1300, 0, 0);
        // A fourth throw must not influence who qualified for it.
        EventEntry.Attempt late = new EventEntry.Attempt();
        late.number = 4;
        late.distanceCm = 2000;
        b.attempts.add(late);

        List<EventEntry> all = new ArrayList<>(Arrays.asList(a, b, c));
        List<EventEntry> top = EventRanking.finalists(all, 3, 2, true);
        assertEquals(2, top.size());
        assertTrue(top.contains(a));
        assertTrue(top.contains(c));
        assertFalse(top.contains(b));
    }
}
