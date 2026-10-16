package in.flavium.arena.model;

/**
 * One heat or final of a category that has many entrants at once: a race, a relay, a
 * swimming event, a discus or shot put competition. (Sports where two sides meet use
 * Match instead.)
 */
public class EventRound {

    public static final String STAGE_HEAT = "HEAT";
    public static final String STAGE_FINAL = "FINAL";
    public static final String[] STAGES = {STAGE_HEAT, STAGE_FINAL};
    public static final String[] STAGE_LABELS = {"Heat", "Final"};

    public String id;
    public String categoryId;
    public String stage;
    /** 1, 2, 3 ... for heats; 0 for a final. */
    public int heatNumber;
    public String date;
    public String time;
    public String venue;
    /** Same statuses as a match: UPCOMING, LIVE, PAUSED, COMPLETED, POSTPONED. */
    public String status;
    public boolean isFinal;
    public String assignedTo;
    public long updatedAt;
    public String updatedBy;
    public int version;

    // Filled in by the repository for display
    public String categoryName;
    public String sportName;
    public String sportId;
    public String categoryEngine;
    public String categoryRules;
    public String assignedName;
    public String updatedByName;
    public int entryCount;

    public boolean isHeat() {
        return STAGE_HEAT.equals(stage);
    }

    public String title() {
        return isHeat() ? "Heat " + heatNumber : "Final";
    }

    public RuleSet rules() {
        return RuleSet.parse(categoryRules);
    }

    public boolean isMeasured() {
        return SportTemplate.ENGINE_MEASURED.equals(categoryEngine);
    }

    public String statusLabel() {
        return isFinal ? "OFFICIAL" : status;
    }
}
