package com.example.flaviumarena.scoring;

import com.example.flaviumarena.model.RuleSet;

/**
 * The set rules of one category, read from its saved rule values.
 *
 * "Set" is used for whatever the sport calls one part of a match: a set in volleyball,
 * a game in badminton and table tennis, a frame in pool, a pull in tug of war. In lawn
 * tennis a set is counted in games.
 *
 * Nothing here is fixed in code: the number of sets, the target, the winning margin,
 * the cap and the deciding-set rule all come from the category.
 */
public class SetRules {

    /** What one part is called, for messages: "Set", "Game", "Frame"... */
    public String unit = "Set";
    /** The match is the best of this many sets (always odd). */
    public int bestOf = 1;
    /** Score needed to win a set. */
    public int target = 1;
    /** Lead needed to win a set. 2 means play continues at deuce. */
    public int winBy = 1;
    /** A set ends when someone reaches this score, whatever the lead. 0 = no cap. */
    public int cap;
    /** Target of the deciding set, when it differs. 0 = same as the other sets. */
    public int decidingTarget;

    // Lawn tennis
    /** At "tiebreakAt" games each, one tie-break game decides the set. */
    public boolean tiebreak;
    public int tiebreakAt;
    public int tiebreakPoints = 7;
    /** The deciding set is replaced by a single tie-break to 10 points. */
    public boolean decidingMatchTiebreak;
    /** Games are scored point by point (0, 15, 30, 40). */
    public boolean pointByPoint;
    /** At deuce the next point wins the game (no advantage). */
    public boolean noAd;

    public static SetRules from(RuleSet rules) {
        SetRules r = new SetRules();
        r.unit = rules.get("set_unit", "Set");
        r.bestOf = Math.max(1, rules.getInt("sets_best_of", 1));
        r.target = Math.max(1, rules.getInt("set_target", 1));
        r.winBy = Math.max(1, rules.getInt("set_win_by", 1));
        r.cap = Math.max(0, rules.getInt("set_cap", 0));
        r.decidingTarget = Math.max(0, rules.getInt("deciding_target", 0));
        r.tiebreak = rules.isYes("tiebreak");
        r.tiebreakAt = rules.getInt("tiebreak_at", r.target);
        r.tiebreakPoints = Math.max(1, rules.getInt("tiebreak_points", 7));
        r.decidingMatchTiebreak = rules.get("deciding_set", "").startsWith("Match tie-break");
        r.pointByPoint = rules.has("point_scoring");
        r.noAd = rules.get("point_scoring", "").startsWith("No-ad");
        return r;
    }

    /** Sets a side must win to win the match: 2 in a best of 3, 3 in a best of 5. */
    public int setsToWin() {
        return bestOf / 2 + 1;
    }

    /** The last possible set of the match. */
    public boolean isDeciding(int setNumber) {
        return bestOf > 1 && setNumber == bestOf;
    }

    public int targetOf(int setNumber) {
        if (isDeciding(setNumber)) {
            if (decidingMatchTiebreak) {
                return 10;
            }
            if (decidingTarget > 0) {
                return decidingTarget;
            }
        }
        return target;
    }

    public int winByOf(int setNumber) {
        return isDeciding(setNumber) && decidingMatchTiebreak ? 2 : winBy;
    }

    /** The cap only makes sense when it is not below the target of that set. */
    public int capOf(int setNumber) {
        if (isDeciding(setNumber) && decidingMatchTiebreak) {
            return 0;
        }
        return cap >= targetOf(setNumber) ? cap : 0;
    }

    /** Is this set decided by a tie-break game when the games are level? */
    public boolean tiebreakIn(int setNumber) {
        return tiebreak && !(isDeciding(setNumber) && decidingMatchTiebreak);
    }
}
