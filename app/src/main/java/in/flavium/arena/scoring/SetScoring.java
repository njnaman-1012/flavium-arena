package in.flavium.arena.scoring;

import java.util.List;

/**
 * The arithmetic of set-based scoring. Pure Java with no Android code, so every rule
 * here is covered by unit tests.
 *
 * The central rule: reaching the target does NOT win a set by itself. A set is won
 * when a side has reached the target AND leads by the required margin, or has reached
 * the cap. So with a target of 15 and a margin of 2, 15-14 is still in play and 16-14
 * wins.
 */
public final class SetScoring {

    public static final int NOBODY = 0;
    public static final int SIDE_A = 1;
    public static final int SIDE_B = 2;

    /** Where a match stands after looking at all its sets. */
    public static class State {
        public int setsA;
        public int setsB;
        /** NOBODY while the match is still open. */
        public int winner;
        /** The set being played, or the next one to start. 0 once the match is decided. */
        public int currentSet;
        /** A problem with the list of sets, or null when it is consistent. */
        public String error;
    }

    private SetScoring() {
    }

    /** Has the set ended at this score? */
    private static boolean isFinished(SetRules r, int setNumber, int a, int b) {
        int high = Math.max(a, b);
        int low = Math.min(a, b);
        if (high >= r.targetOf(setNumber) && high - low >= r.winByOf(setNumber)) {
            return true;
        }
        int cap = r.capOf(setNumber);
        if (cap > 0 && high >= cap) {
            return true;
        }
        // Lawn tennis: 7-6 after a tie-break.
        return r.tiebreakIn(setNumber) && high == r.tiebreakAt + 1 && low == r.tiebreakAt;
    }

    /** Who won the set at this score: SIDE_A, SIDE_B, or NOBODY while it is in play. */
    public static int winnerOfSet(SetRules r, int setNumber, int a, int b) {
        if (a == b || !isFinished(r, setNumber, a, b)) {
            return NOBODY;
        }
        return a > b ? SIDE_A : SIDE_B;
    }

    /**
     * Checks that a set score could really occur. Returns null when it could.
     * A score is impossible when the set would already have ended one point earlier,
     * for example 27-10 in a set to 25, or 17-14 when 16-14 already won it.
     */
    public static String problemWith(SetRules r, int setNumber, int a, int b) {
        if (a < 0 || b < 0) {
            return "Scores cannot be negative.";
        }
        String unit = r.unit.toLowerCase();
        int high = Math.max(a, b);
        int low = Math.min(a, b);
        int cap = r.capOf(setNumber);
        if (cap > 0 && high > cap) {
            return "A " + unit + " cannot go above " + cap + ".";
        }
        if (r.tiebreakIn(setNumber) && low == r.tiebreakAt && high > r.tiebreakAt + 1) {
            return "At " + r.tiebreakAt + "-" + r.tiebreakAt + " a tie-break decides the "
                    + unit + ", so it ends " + (r.tiebreakAt + 1) + "-" + r.tiebreakAt + ".";
        }
        if (high > 0 && a != b && isFinished(r, setNumber, high - 1, low)
                && Math.max(high - 1, low) == high - 1 && high - 1 != low) {
            return "This " + unit + " was already won at " + (high - 1) + "-" + low
                    + ", so the score cannot be " + high + "-" + low + ".";
        }
        if (a == b && isFinished(r, setNumber, a, b)) {
            return "A " + unit + " cannot end level at " + a + "-" + b + ".";
        }
        return null;
    }

    /**
     * Works out the state of a match from its sets, given in order as {a, b} pairs.
     * Every set except the last must be finished, and no set may follow the one that
     * decided the match.
     */
    public static State evaluate(SetRules r, List<int[]> sets) {
        State state = new State();
        int number = 0;
        for (int[] set : sets) {
            number++;
            if (state.winner != NOBODY) {
                state.error = "The match was already decided before " + r.unit.toLowerCase()
                        + " " + number + ".";
                return state;
            }
            if (number > r.bestOf) {
                state.error = "This match has at most " + r.bestOf + " "
                        + r.unit.toLowerCase() + "s.";
                return state;
            }
            String problem = problemWith(r, number, set[0], set[1]);
            if (problem != null) {
                state.error = r.unit + " " + number + ": " + problem;
                return state;
            }
            int won = winnerOfSet(r, number, set[0], set[1]);
            if (won == NOBODY) {
                if (number < sets.size()) {
                    state.error = r.unit + " " + number + " is not finished, so "
                            + r.unit.toLowerCase() + " " + (number + 1) + " cannot be scored.";
                    return state;
                }
                state.currentSet = number;
                return state;
            }
            if (won == SIDE_A) {
                state.setsA++;
            } else {
                state.setsB++;
            }
            if (state.setsA >= r.setsToWin()) {
                state.winner = SIDE_A;
            } else if (state.setsB >= r.setsToWin()) {
                state.winner = SIDE_B;
            }
        }
        // Every set so far is finished: the next one is current, unless the match is over.
        state.currentSet = state.winner == NOBODY ? number + 1 : 0;
        return state;
    }
}
