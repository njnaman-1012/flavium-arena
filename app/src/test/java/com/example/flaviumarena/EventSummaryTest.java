package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;

import com.example.flaviumarena.model.EventEntry;
import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.scoring.EventSummary;

import org.junit.Test;

/** The words that describe a heat, a final and its entrants. Runs on the computer. */
public class EventSummaryTest {

    private static RuleSet rules(String... pairs) {
        RuleSet rules = new RuleSet();
        for (int i = 0; i < pairs.length; i += 2) {
            rules.put(pairs[i], pairs[i + 1]);
        }
        return rules;
    }

    private static EventRound round(String engine, String stage, RuleSet rules) {
        EventRound r = new EventRound();
        r.categoryEngine = engine;
        r.stage = stage;
        r.categoryRules = rules.toText();
        return r;
    }

    private static EventEntry.Attempt attempt(int number, Integer cm) {
        EventEntry.Attempt a = new EventEntry.Attempt();
        a.number = number;
        a.distanceCm = cm;
        a.foul = cm == null;
        return a;
    }

    @Test
    public void attemptsAreCountedFromTheRules() {
        RuleSet shotPut = rules("attempts", "3", "finalists", "8", "extra_attempts", "3");
        assertEquals(3, EventSummary.attempts(shotPut));
        assertEquals(8, EventSummary.finalists(shotPut));
        assertEquals(3, EventSummary.extraAttempts(shotPut));
        assertEquals(6, EventSummary.attemptsAllowed(shotPut));

        // Without finalists nobody gets extra attempts, whatever the number says.
        RuleSet simple = rules("attempts", "4", "finalists", "0", "extra_attempts", "3");
        assertEquals(0, EventSummary.extraAttempts(simple));
        assertEquals(4, EventSummary.attemptsAllowed(simple));
        // A category without these rules gets three attempts.
        assertEquals(3, EventSummary.attemptsAllowed(new RuleSet()));
    }

    @Test
    public void theResultOfARaceIsItsTime() {
        EventEntry e = new EventEntry();
        assertEquals("", EventSummary.result(false, e));
        assertEquals("", EventSummary.badge(false, e));
        e.timeMs = 62350;
        assertEquals("1:02.35", EventSummary.result(false, e));
        assertEquals("1:02.35", EventSummary.badge(false, e));
        // A disqualified runner shows the reason instead of the time.
        e.resultStatus = EventEntry.STATUS_DQ;
        assertEquals("DQ", EventSummary.badge(false, e));
        assertEquals("1:02.35", EventSummary.result(false, e));
    }

    @Test
    public void theResultOfAThrowIsTheBestDistanceInMetres() {
        EventEntry e = new EventEntry();
        assertEquals("", EventSummary.badge(true, e));
        e.bestCm = 1245;
        e.attempts.add(attempt(1, 1245));
        e.attempts.add(attempt(2, null));
        e.attempts.add(attempt(3, 1190));
        assertEquals("12.45", EventSummary.result(true, e));
        assertEquals("12.45 m", EventSummary.badge(true, e));
        assertEquals("1: 12.45   2: foul   3: 11.90", EventSummary.attemptList(e));
        assertEquals("foul", EventSummary.attempt(e.attempts.get(1)));
        e.resultStatus = EventEntry.STATUS_DNS;
        assertEquals("DNS", EventSummary.badge(true, e));
    }

    @Test
    public void theRulesLineSuitsTheKindOfEvent() {
        RuleSet race = rules("lanes", "6", "qualify_per_heat", "2", "fastest_losers", "2");
        assertEquals("Lanes: 6\nQualifying: first 2 of each heat, plus the 2 fastest of the "
                + "rest", EventSummary.rules(round(SportTemplate.ENGINE_TIMED,
                EventRound.STAGE_HEAT, race)));
        // Nobody qualifies from a final, so only the lanes are mentioned.
        assertEquals("Lanes: 6", EventSummary.rules(round(SportTemplate.ENGINE_TIMED,
                EventRound.STAGE_FINAL, race)));
        assertEquals("Lanes: 8\nQualifying: first 3 of each heat",
                EventSummary.rules(round(SportTemplate.ENGINE_TIMED, EventRound.STAGE_HEAT,
                        rules("qualify_per_heat", "3", "fastest_losers", "0"))));

        assertEquals("Attempts: 3, then 3 more for the best 8",
                EventSummary.rules(round(SportTemplate.ENGINE_MEASURED, EventRound.STAGE_FINAL,
                        rules("attempts", "3", "finalists", "8", "extra_attempts", "3"))));
        assertEquals("Attempts: 4", EventSummary.rules(round(SportTemplate.ENGINE_MEASURED,
                EventRound.STAGE_FINAL, rules("attempts", "4"))));
    }
}
