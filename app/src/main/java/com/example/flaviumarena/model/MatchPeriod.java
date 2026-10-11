package com.example.flaviumarena.model;

/**
 * One part of a match, kept as its own row so the breakdown is never lost.
 *
 *   Sets engine:    one row per set (or game, frame, pull). scoreA and scoreB are the
 *                   points of each side; for lawn tennis they are games, tbA and tbB the
 *                   tie-break points, and ptA and ptB the points of the game in play.
 *   Cricket engine: one row per innings. "side" says who batted, scoreA holds the runs,
 *                   and wickets, balls and extras complete the innings.
 */
public class MatchPeriod {
    public String id;
    public String matchId;
    /** 1, 2, 3 ... in playing order. */
    public int number;
    /** Cricket only: "A" or "B", the side that batted. */
    public String side = "";
    public int scoreA;
    public int scoreB;
    public int wickets;
    /** Cricket: legal balls bowled in the innings (overs x 6 + balls). */
    public int balls;
    public int extras;
    public int tbA;
    public int tbB;
    public int ptA;
    public int ptB;

    /** Cricket: "12.4" for twelve overs and four balls. */
    public String oversText() {
        return (balls / 6) + "." + (balls % 6);
    }
}
