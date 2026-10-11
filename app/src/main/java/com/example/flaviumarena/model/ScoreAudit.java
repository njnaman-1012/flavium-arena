package com.example.flaviumarena.model;

/** One entry in the history of changes made to a match score. */
public class ScoreAudit {

    public static final String ACTION_SCORE_UPDATE = "SCORE_UPDATE";
    public static final String ACTION_FINALISED = "FINALISED";
    public static final String ACTION_CORRECTION = "CORRECTION";
    public static final String ACTION_STATUS = "STATUS";
    public static final String ACTION_REOPENED = "REOPENED";

    public String id;
    public String matchId;
    public String changedByName;
    public long changedAt;
    public String action;
    public String oldScore;
    public String newScore;
    public String reason;
}
