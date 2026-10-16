package in.flavium.arena.model;

/** One line of the medal table: the medals of one branch. There are no points. */
public class MedalRow {
    public String branchId;
    public String branchName;
    public String branchCode;
    public int gold;
    public int silver;
    public int bronze;
    /** Place in the table. Branches with exactly the same medals share a place. */
    public int rank;

    public int total() {
        return gold + silver + bronze;
    }
}
