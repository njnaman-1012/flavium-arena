package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;

import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.MatchPeriod;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.scoring.MatchSummary;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The words under the score of a match. Runs on the computer. */
public class MatchSummaryTest {

    private static Match teamMatch() {
        Match m = new Match();
        m.categoryType = Category.TYPE_TEAM;
        m.teamAName = "Computer Engineering — Year 3";
        m.branchACode = "CE";
        m.yearA = 3;
        m.teamBName = "Mechanical Engineering — Year 3";
        m.branchBCode = "ME";
        m.yearB = 3;
        m.status = Match.STATUS_LIVE;
        return m;
    }

    private static MatchPeriod innings(String side, int runs, int wickets) {
        MatchPeriod p = new MatchPeriod();
        p.side = side;
        p.scoreA = runs;
        p.wickets = wickets;
        return p;
    }

    private static RuleSet rules(String... pairs) {
        RuleSet rules = new RuleSet();
        for (int i = 0; i < pairs.length; i += 2) {
            rules.put(pairs[i], pairs[i + 1]);
        }
        return rules;
    }

    @Test
    public void periodsAreNamedAndFollowedByExtraTimeAndTheShootOut() {
        RuleSet football = rules("periods", "2", "period_name", "Half");
        assertEquals("Half 1 of 2", MatchSummary.periodName(football, 1));
        assertEquals("Half 2 of 2", MatchSummary.periodName(football, 2));
        assertEquals("Extra time", MatchSummary.periodName(football, 3));
        assertEquals("Shoot-out", MatchSummary.periodName(football, 4));
        // A category without periods still gets a sensible name.
        assertEquals("Period 1", MatchSummary.periodName(new RuleSet(), 1));
    }

    @Test
    public void winDrawOrLossIsSaidInWords() {
        Match m = teamMatch();
        assertEquals("No result yet", MatchSummary.result(m));
        m.scoreA = 1;
        m.scoreB = 0;
        assertEquals("CE Y3 won", MatchSummary.result(m));
        m.scoreA = 0;
        m.scoreB = 1;
        assertEquals("ME Y3 won", MatchSummary.result(m));
        m.scoreB = 0;
        assertEquals("Draw", MatchSummary.result(m));
    }

    @Test
    public void cricketShowsTheTargetWhileTheChaseIsOn() {
        Match m = teamMatch();
        RuleSet rules = rules(RuleSet.TEAM_MIN, "11");
        List<MatchPeriod> innings = new ArrayList<>();
        assertEquals("Runs. No innings entered yet", MatchSummary.cricket(m, innings, rules));
        innings.add(innings("A", 120, 6));
        assertEquals("Runs. Target for the side batting second: 121",
                MatchSummary.cricket(m, innings, rules));
        innings.add(innings("B", 40, 1));
        assertEquals("Runs. Chasing 121", MatchSummary.cricket(m, innings, rules));
    }

    @Test
    public void cricketResultIsByRunsOrByWickets() {
        Match m = teamMatch();
        m.status = Match.STATUS_COMPLETED;
        RuleSet rules = rules(RuleSet.TEAM_MIN, "11");

        // The side batting second falls short: the first side wins by the difference.
        assertEquals("CE Y3 won by 12 runs", MatchSummary.cricket(m,
                Arrays.asList(innings("A", 120, 6), innings("B", 108, 10)), rules));
        // The side batting second passes the total with four wickets down.
        assertEquals("ME Y3 won by 6 wickets", MatchSummary.cricket(m,
                Arrays.asList(innings("A", 120, 6), innings("B", 121, 4)), rules));
        // The side batting first can be either side.
        assertEquals("ME Y3 won by 5 runs", MatchSummary.cricket(m,
                Arrays.asList(innings("B", 90, 10), innings("A", 85, 10)), rules));
        assertEquals("Scores level", MatchSummary.cricket(m,
                Arrays.asList(innings("A", 100, 5), innings("B", 100, 9)), rules));
        // A seven-a-side game has six wickets to lose.
        assertEquals("ME Y3 won by 4 wickets", MatchSummary.cricket(m,
                Arrays.asList(innings("A", 50, 6), innings("B", 51, 2)),
                rules(RuleSet.TEAM_MIN, "7")));
    }
}
