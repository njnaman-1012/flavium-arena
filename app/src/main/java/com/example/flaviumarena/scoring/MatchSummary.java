package com.example.flaviumarena.scoring;

import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.MatchPeriod;
import com.example.flaviumarena.model.RuleSet;

import java.util.List;

/**
 * The words that describe the state of a match under its score. Pure Java, covered by
 * unit tests. The match screen only decides where the text goes.
 */
public final class MatchSummary {

    private MatchSummary() {
    }

    /**
     * The name of a period of play: "Half 1 of 2", then "Extra time" and "Shoot-out"
     * after the regular periods.
     */
    public static String periodName(RuleSet rules, int number) {
        int count = rules.getInt("periods", 0);
        if (count > 0 && number == count + 1) {
            return "Extra time";
        }
        if (count > 0 && number == count + 2) {
            return "Shoot-out";
        }
        return rules.get("period_name", "Period") + " " + number
                + (count > 0 ? " of " + count : "");
    }

    /** Win, draw or loss sports (chess): who won, in words. */
    public static String result(Match match) {
        if (!match.hasScore()) {
            return "No result yet";
        }
        int winner = match.winnerSide();
        return winner == 0 ? "Draw" : (winner == 1 ? match.sideAShort() : match.sideBShort())
                + " won";
    }

    /**
     * Cricket: the state of the match worked out from the innings totals. The target
     * while the second side bats; "won by 12 runs" or "won by 4 wickets" at the end.
     */
    public static String cricket(Match match, List<MatchPeriod> innings, RuleSet rules) {
        if (innings.isEmpty()) {
            return "Runs. No innings entered yet";
        }
        MatchPeriod first = innings.get(0);
        if (innings.size() == 1) {
            return "Runs. Target for the side batting second: " + (first.scoreA + 1);
        }
        MatchPeriod second = innings.get(1);
        if (!Match.STATUS_COMPLETED.equals(match.status)) {
            return "Runs. Chasing " + (first.scoreA + 1);
        }
        String battedFirst = "A".equals(first.side) ? match.sideAShort() : match.sideBShort();
        String battedSecond = "A".equals(second.side) ? match.sideAShort() : match.sideBShort();
        if (second.scoreA > first.scoreA) {
            // A side is all out when all but one of its players are out.
            int wicketsLeft = Math.max(1, rules.getInt(RuleSet.TEAM_MIN, 11) - 1) - second.wickets;
            return battedSecond + " won by " + wicketsLeft + " wickets";
        }
        if (second.scoreA < first.scoreA) {
            return battedFirst + " won by " + (first.scoreA - second.scoreA) + " runs";
        }
        return "Scores level";
    }
}
