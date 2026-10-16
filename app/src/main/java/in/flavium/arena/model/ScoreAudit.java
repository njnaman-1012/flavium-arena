package in.flavium.arena.model;

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

    /**
     * True for the history of a heat or final, where newScore is a whole sentence
     * ("Lane 2 Asha Rao: 12.34") instead of a score that replaced oldScore.
     */
    public boolean detailOnly;
}
