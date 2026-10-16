package in.flavium.arena.model;

/** One line of a points table. */
public class StandingRow {
    public String registrationId;
    public String name;
    /** For an individual: the branch and year they represent. Empty for a team. */
    public String detail = "";
    public int played;
    public int won;
    public int drawn;
    public int lost;
    public int scored;
    public int conceded;
    public int points;

    public int difference() {
        return scored - conceded;
    }
}
