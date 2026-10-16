package in.flavium.arena.scoring;

import in.flavium.arena.model.EventEntry;
import in.flavium.arena.model.EventRound;
import in.flavium.arena.model.RuleSet;

/**
 * The words and numbers that describe a heat, a final and its entrants. Pure Java, covered
 * by unit tests, so the event screen, the standings and the downloads all say the same.
 */
public final class EventSummary {

    private EventSummary() {
    }

    /** How many attempts every entrant has before the best ones get more. */
    public static int attempts(RuleSet rules) {
        return Math.max(1, rules.getInt("attempts", 3));
    }

    /** How many entrants go on to the extra attempts; 0 when there are none. */
    public static int finalists(RuleSet rules) {
        return Math.max(0, rules.getInt("finalists", 0));
    }

    /** Extra attempts for those finalists; 0 when the event has no finalists. */
    public static int extraAttempts(RuleSet rules) {
        return finalists(rules) > 0 ? Math.max(0, rules.getInt("extra_attempts", 0)) : 0;
    }

    /** The highest attempt number anyone can have in this event. */
    public static int attemptsAllowed(RuleSet rules) {
        return attempts(rules) + extraAttempts(rules);
    }

    /**
     * The result that counts for an entrant, without a unit: the time of a race
     * ("12.34") or the best valid distance of a throw ("12.45"). Empty when there is none.
     */
    public static String result(boolean measured, EventEntry e) {
        if (measured) {
            return e.bestCm == null ? "" : Measure.formatDistance(e.bestCm);
        }
        return e.timeMs == null ? "" : Measure.formatTime(e.timeMs);
    }

    /**
     * What is shown beside an entrant: DQ, DNS or DNF when the result does not count,
     * otherwise the result, with "m" for a distance.
     */
    public static String badge(boolean measured, EventEntry e) {
        if (!e.hasNormalStatus()) {
            return e.resultStatus;
        }
        String result = result(measured, e);
        return measured && !result.isEmpty() ? result + " m" : result;
    }

    /** Every attempt of a thrower in order, for example "1: 12.45   2: foul". */
    public static String attemptList(EventEntry e) {
        StringBuilder text = new StringBuilder();
        for (EventEntry.Attempt a : e.attempts) {
            text.append(text.length() > 0 ? "   " : "").append(a.number).append(": ")
                    .append(attempt(a));
        }
        return text.toString();
    }

    /** One attempt: its distance, or "foul". */
    public static String attempt(EventEntry.Attempt a) {
        return a.foul || a.distanceCm == null ? "foul" : Measure.formatDistance(a.distanceCm);
    }

    /**
     * The rules that matter while the event is run, in one or two lines:
     * attempts for a throw; lanes and who qualifies for a race.
     */
    public static String rules(EventRound round) {
        RuleSet rules = round.rules();
        if (round.isMeasured()) {
            String text = "Attempts: " + attempts(rules);
            if (extraAttempts(rules) > 0) {
                text += ", then " + extraAttempts(rules) + " more for the best "
                        + finalists(rules);
            }
            return text;
        }
        String text = "Lanes: " + Math.max(1, rules.getInt("lanes", 8));
        if (round.isHeat()) {
            text += "\nQualifying: first " + rules.getInt("qualify_per_heat", 0)
                    + " of each heat";
            int fastest = rules.getInt("fastest_losers", 0);
            if (fastest > 0) {
                text += ", plus the " + fastest + " fastest of the rest";
            }
        }
        return text;
    }
}
