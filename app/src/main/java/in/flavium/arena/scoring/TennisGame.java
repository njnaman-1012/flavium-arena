package in.flavium.arena.scoring;

/**
 * Points inside one game of lawn tennis. Points are counted 0, 1, 2, 3... and shown as
 * 0, 15, 30, 40. Pure Java, covered by unit tests.
 */
public final class TennisGame {

    private static final String[] NAMES = {"0", "15", "30", "40"};

    private TennisGame() {
    }

    /**
     * Who has won the game: SetScoring.SIDE_A, SIDE_B, or NOBODY.
     *   Advantage scoring: four points and two clear. At 40-40 (deuce) a side must win
     *                      two points in a row: advantage, then game.
     *   No-ad scoring:     four points wins; at deuce the next point decides.
     */
    public static int winner(int a, int b, boolean noAd) {
        int lead = noAd ? 1 : 2;
        if (a >= 4 && a - b >= lead) {
            return SetScoring.SIDE_A;
        }
        if (b >= 4 && b - a >= lead) {
            return SetScoring.SIDE_B;
        }
        return SetScoring.NOBODY;
    }

    /** The score of a game in words, for example "40 - 30", "Deuce" or "Advantage A". */
    public static String label(int a, int b) {
        if (a >= 3 && b >= 3) {
            if (a == b) {
                return "Deuce";
            }
            return a > b ? "Advantage A" : "Advantage B";
        }
        return NAMES[Math.min(a, 3)] + " - " + NAMES[Math.min(b, 3)];
    }

    /** A tie-break is won by reaching the target with a lead of two points. */
    public static int tiebreakWinner(int a, int b, int target) {
        if (a >= target && a - b >= 2) {
            return SetScoring.SIDE_A;
        }
        if (b >= target && b - a >= 2) {
            return SetScoring.SIDE_B;
        }
        return SetScoring.NOBODY;
    }
}
