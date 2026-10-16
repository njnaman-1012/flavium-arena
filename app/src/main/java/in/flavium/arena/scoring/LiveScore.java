package in.flavium.arena.scoring;

/**
 * What one tap on + or − does to a score with one number per side (football,
 * basketball). Pure Java, covered by unit tests; the match screen saves the result at
 * once through the repository, which checks it again.
 */
public final class LiveScore {

    private LiveScore() {
    }

    /**
     * The score after one tap: exactly one more or one fewer for one side. A match that
     * has no score yet starts at 0 - 0. Returns null when the tap would take a side
     * below 0, so nothing is saved.
     */
    public static int[] tap(Integer scoreA, Integer scoreB, boolean sideA, int delta) {
        if (delta != 1 && delta != -1) {
            throw new IllegalArgumentException("A tap changes a score by one.");
        }
        int a = (scoreA == null ? 0 : scoreA) + (sideA ? delta : 0);
        int b = (scoreB == null ? 0 : scoreB) + (sideA ? 0 : delta);
        return a < 0 || b < 0 ? null : new int[]{a, b};
    }

    /** A penalty shoot-out belongs to a level score; it is dropped when that changes. */
    public static boolean keepsShootOut(Integer scoreA, Integer scoreB) {
        return scoreA != null && scoreA.equals(scoreB);
    }
}
