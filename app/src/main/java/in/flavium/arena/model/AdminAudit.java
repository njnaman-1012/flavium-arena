package in.flavium.arena.model;

/** One entry in the log of account, role and settings changes. Faculty can read it. */
public class AdminAudit {
    public String id;
    public String actorName;
    public long changedAt;
    public String action;
    public String detail;
}
