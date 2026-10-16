package in.flavium.arena;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import in.flavium.arena.scoring.LiveScore;

import org.junit.Test;

/** One tap on + or − in live scoring. Runs on the computer. */
public class LiveScoreTest {

    @Test
    public void aTapChangesOneSideByExactlyOne() {
        assertArrayEquals(new int[]{3, 1}, LiveScore.tap(2, 1, true, 1));
        assertArrayEquals(new int[]{2, 2}, LiveScore.tap(2, 1, false, 1));
        assertArrayEquals(new int[]{1, 1}, LiveScore.tap(2, 1, true, -1));
        assertArrayEquals(new int[]{2, 0}, LiveScore.tap(2, 1, false, -1));
    }

    @Test
    public void aMatchWithoutAScoreStartsAtNilNil() {
        assertArrayEquals(new int[]{1, 0}, LiveScore.tap(null, null, true, 1));
        assertArrayEquals(new int[]{0, 1}, LiveScore.tap(null, null, false, 1));
    }

    @Test
    public void aScoreNeverGoesBelowZero() {
        assertNull(LiveScore.tap(0, 3, true, -1));
        assertNull(LiveScore.tap(null, null, false, -1));
        assertArrayEquals(new int[]{0, 2}, LiveScore.tap(0, 3, false, -1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aTapIsNeverMoreThanOne() {
        LiveScore.tap(1, 1, true, 2);
    }

    @Test
    public void aShootOutBelongsToALevelScoreOnly() {
        assertTrue(LiveScore.keepsShootOut(1, 1));
        assertFalse(LiveScore.keepsShootOut(2, 1));
        assertFalse(LiveScore.keepsShootOut(null, null));
    }
}
