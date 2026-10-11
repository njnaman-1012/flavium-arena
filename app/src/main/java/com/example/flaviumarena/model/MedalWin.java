package com.example.flaviumarena.model;

/**
 * One medal won in one category. Medals are never stored: they are worked out from the
 * official results every time, so they cannot disagree with them.
 */
public class MedalWin {
    /** GOLD, SILVER or BRONZE (see scoring.Medals). */
    public String medal;

    public String categoryId;
    public String categoryName;
    public String sportName;

    /** The team or participant who won it. */
    public String registrationId;
    public String name;
    public boolean team;
    public int year;

    /** The branch the medal counts for, or null when the winner has no branch. */
    public String branchId;
    public String branchName;
    public String branchCode;
}
