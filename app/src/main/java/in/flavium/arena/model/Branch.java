package in.flavium.arena.model;

/** A college branch (department). Teams compete on behalf of a branch. */
public class Branch {
    public String id;
    public String name;
    /** Short form shown where space is tight, for example "CE". */
    public String code;
    public boolean active = true;
}
