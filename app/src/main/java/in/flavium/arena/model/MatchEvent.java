package in.flavium.arena.model;

/** Something that happened in a match, such as a goal or a card. Shown to everyone. */
public class MatchEvent {

    /** Kinds of event offered when one is recorded. */
    public static final String[] TYPES = {"Goal", "Own goal", "Yellow card", "Red card",
            "Foul", "Substitution", "Time-out", "Other"};

    public String id;
    public String matchId;
    /** Minute of the match, or 0 when not known. */
    public int minute;
    /** "A" or "B". */
    public String side;
    public String type;
    /** Player involved, optional. */
    public String player;
    public long createdAt;
}
