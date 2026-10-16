package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import in.flavium.arena.model.RuleSet;
import in.flavium.arena.scoring.SetRules;
import in.flavium.arena.scoring.SetScoring;
import in.flavium.arena.scoring.TennisGame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Set, deuce, cap, deciding-set and tennis rules. Runs on the computer, no phone needed. */
public class SetScoringTest {

    private static SetRules rules(int bestOf, int target, int winBy, int cap, int deciding) {
        SetRules r = new SetRules();
        r.bestOf = bestOf;
        r.target = target;
        r.winBy = winBy;
        r.cap = cap;
        r.decidingTarget = deciding;
        return r;
    }

    private static List<int[]> sets(int... scores) {
        List<int[]> list = new ArrayList<>();
        for (int i = 0; i < scores.length; i += 2) {
            list.add(new int[]{scores[i], scores[i + 1]});
        }
        return list;
    }

    // ----- The example from the requirements: best of three, sets to 15, win by two -----

    private final SetRules shortVolleyball = rules(3, 15, 2, 0, 0);

    @Test
    public void reachingTheTargetDoesNotWinWithoutTheMargin() {
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(shortVolleyball, 1, 15, 14));
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(shortVolleyball, 1, 16, 14));
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(shortVolleyball, 1, 15, 13));
        assertEquals(SetScoring.SIDE_B, SetScoring.winnerOfSet(shortVolleyball, 1, 20, 22));
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(shortVolleyball, 1, 21, 20));
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(shortVolleyball, 1, 14, 3));
    }

    @Test
    public void aScoreBeyondTheEndOfASetIsRejected() {
        assertNull(SetScoring.problemWith(shortVolleyball, 1, 15, 14));   // deuce, in play
        assertNull(SetScoring.problemWith(shortVolleyball, 1, 16, 14));   // just won
        assertNull(SetScoring.problemWith(shortVolleyball, 1, 15, 3));    // just won
        assertNotNull(SetScoring.problemWith(shortVolleyball, 1, 17, 14)); // was won at 16-14
        assertNotNull(SetScoring.problemWith(shortVolleyball, 1, 18, 3));  // was won at 15-3
        assertNotNull(SetScoring.problemWith(shortVolleyball, 1, -1, 3));
    }

    @Test
    public void theTargetIsNotHardCoded() {
        SetRules fullVolleyball = rules(5, 25, 2, 0, 15);
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(fullVolleyball, 1, 15, 3));
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(fullVolleyball, 1, 25, 23));
        // The fifth set is the deciding one and is played to 15.
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(fullVolleyball, 5, 15, 13));
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(fullVolleyball, 4, 15, 13));
        assertEquals(3, fullVolleyball.setsToWin());
    }

    @Test
    public void aCapEndsTheSetWhateverTheLead() {
        SetRules badminton = rules(3, 21, 2, 30, 0);
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(badminton, 1, 29, 28));
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(badminton, 1, 30, 29));
        assertNull(SetScoring.problemWith(badminton, 1, 30, 29));
        assertNotNull(SetScoring.problemWith(badminton, 1, 31, 29));
    }

    @Test
    public void aSetNeedingOnlyOneWinWorksForFramesAndPulls() {
        SetRules pool = rules(5, 1, 1, 0, 0);
        assertEquals(SetScoring.SIDE_B, SetScoring.winnerOfSet(pool, 1, 0, 1));
        assertNotNull(SetScoring.problemWith(pool, 1, 2, 0));
        SetScoring.State state = SetScoring.evaluate(pool, sets(1, 0, 0, 1, 1, 0, 1, 0));
        assertNull(state.error);
        assertEquals(3, state.setsA);
        assertEquals(1, state.setsB);
        assertEquals(SetScoring.SIDE_A, state.winner);
    }

    // ----- A whole match -----

    @Test
    public void matchStateFollowsTheSets() {
        SetScoring.State none = SetScoring.evaluate(shortVolleyball, sets());
        assertEquals(1, none.currentSet);
        assertEquals(SetScoring.NOBODY, none.winner);

        SetScoring.State live = SetScoring.evaluate(shortVolleyball, sets(15, 10, 7, 5));
        assertNull(live.error);
        assertEquals(1, live.setsA);
        assertEquals(0, live.setsB);
        assertEquals(2, live.currentSet);
        assertEquals(SetScoring.NOBODY, live.winner);

        SetScoring.State between = SetScoring.evaluate(shortVolleyball, sets(15, 10, 12, 15));
        assertEquals(3, between.currentSet); // one set each: the deciding set is next

        SetScoring.State done = SetScoring.evaluate(shortVolleyball, sets(15, 10, 12, 15, 17, 15));
        assertNull(done.error);
        assertEquals(2, done.setsA);
        assertEquals(1, done.setsB);
        assertEquals(SetScoring.SIDE_A, done.winner);
        assertEquals(0, done.currentSet);
    }

    @Test
    public void inconsistentSetListsAreRejected() {
        // A set after the match was decided.
        assertNotNull(SetScoring.evaluate(shortVolleyball, sets(15, 10, 15, 8, 3, 0)).error);
        // A set after an unfinished one.
        assertNotNull(SetScoring.evaluate(shortVolleyball, sets(15, 14, 2, 0)).error);
        // An impossible score inside the list.
        assertNotNull(SetScoring.evaluate(shortVolleyball, sets(19, 3)).error);
        // More sets than "best of".
        assertNotNull(SetScoring.evaluate(rules(1, 15, 2, 0, 0), sets(10, 15, 3, 0)).error);
    }

    @Test
    public void rulesAreReadFromTheCategory() {
        RuleSet saved = new RuleSet();
        saved.put("set_unit", "Game");
        saved.put("sets_best_of", "5");
        saved.put("set_target", "11");
        saved.put("set_win_by", "2");
        saved.put("set_cap", "0");
        saved.put("deciding_target", "0");
        SetRules r = SetRules.from(saved);
        assertEquals("Game", r.unit);
        assertEquals(5, r.bestOf);
        assertEquals(11, r.target);
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(r, 1, 11, 10));
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(r, 1, 12, 10));
    }

    // ----- Lawn tennis -----

    private static SetRules tennis() {
        SetRules r = rules(3, 6, 2, 0, 0);
        r.tiebreak = true;
        r.tiebreakAt = 6;
        r.tiebreakPoints = 7;
        r.pointByPoint = true;
        return r;
    }

    @Test
    public void tennisSetsEndAtSixWithTwoClearOrSevenSixAfterATieBreak() {
        SetRules r = tennis();
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(r, 1, 6, 4));
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(r, 1, 6, 5));
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(r, 1, 7, 5));
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(r, 1, 6, 6));
        assertEquals(SetScoring.SIDE_B, SetScoring.winnerOfSet(r, 1, 6, 7));
        assertNull(SetScoring.problemWith(r, 1, 7, 6));
        assertNotNull(SetScoring.problemWith(r, 1, 8, 6));  // the tie-break ends it 7-6
        assertNotNull(SetScoring.problemWith(r, 1, 7, 4));  // was won at 6-4
    }

    @Test
    public void withoutATieBreakTheSetContinuesUntilTwoClear() {
        SetRules r = tennis();
        r.tiebreak = false;
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(r, 1, 7, 6));
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(r, 1, 8, 6));
    }

    @Test
    public void decidingSetCanBeAMatchTieBreakToTen() {
        SetRules r = tennis();
        r.decidingMatchTiebreak = true;
        assertEquals(10, r.targetOf(3));
        assertEquals(6, r.targetOf(2));
        assertEquals(SetScoring.NOBODY, SetScoring.winnerOfSet(r, 3, 10, 9));
        assertEquals(SetScoring.SIDE_A, SetScoring.winnerOfSet(r, 3, 11, 9));
        assertEquals(SetScoring.SIDE_B, SetScoring.winnerOfSet(r, 3, 4, 10));
    }

    @Test
    public void pointsInsideAGameUseDeuceAndAdvantage() {
        assertEquals("0 - 0", TennisGame.label(0, 0));
        assertEquals("40 - 30", TennisGame.label(3, 2));
        assertEquals("Deuce", TennisGame.label(3, 3));
        assertEquals("Advantage A", TennisGame.label(4, 3));
        assertEquals("Deuce", TennisGame.label(4, 4));
        assertEquals("Advantage B", TennisGame.label(5, 6));

        assertEquals(SetScoring.SIDE_A, TennisGame.winner(4, 2, false));
        assertEquals(SetScoring.NOBODY, TennisGame.winner(4, 3, false)); // only advantage
        assertEquals(SetScoring.SIDE_A, TennisGame.winner(5, 3, false));
        assertEquals(SetScoring.SIDE_B, TennisGame.winner(6, 8, false));
        assertEquals(SetScoring.NOBODY, TennisGame.winner(3, 3, false));
    }

    @Test
    public void noAdScoringEndsTheGameOnThePointAfterDeuce() {
        assertEquals(SetScoring.NOBODY, TennisGame.winner(3, 3, true));
        assertEquals(SetScoring.SIDE_A, TennisGame.winner(4, 3, true));
        assertEquals(SetScoring.SIDE_B, TennisGame.winner(3, 4, true));
    }

    @Test
    public void tieBreakNeedsTheTargetAndTwoClear() {
        assertEquals(SetScoring.NOBODY, TennisGame.tiebreakWinner(7, 6, 7));
        assertEquals(SetScoring.SIDE_A, TennisGame.tiebreakWinner(7, 5, 7));
        assertEquals(SetScoring.SIDE_B, TennisGame.tiebreakWinner(8, 10, 7));
        assertEquals(SetScoring.NOBODY, TennisGame.tiebreakWinner(6, 4, 7));
    }
}
