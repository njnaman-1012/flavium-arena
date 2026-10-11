package com.example.flaviumarena.model;

/** A competition inside a sport, for example "Men's 7-a-side" under Football. */
public class Category {

    public static final String TYPE_INDIVIDUAL = "INDIVIDUAL";
    public static final String TYPE_TEAM = "TEAM";
    public static final String[] TYPES = {TYPE_INDIVIDUAL, TYPE_TEAM};
    public static final String[] TYPE_LABELS = {"Individual", "Team"};

    public static final String FORMAT_LEAGUE = "LEAGUE";
    public static final String FORMAT_KNOCKOUT = "KNOCKOUT";
    public static final String[] FORMATS = {FORMAT_LEAGUE, FORMAT_KNOCKOUT};
    public static final String[] FORMAT_LABELS = {"League (points table)", "Knockout"};

    public String id;
    public String sportId;
    public String name;
    public String type;
    public int minSize;
    public int maxSize;
    /** Registration deadline as yyyy-MM-dd, or empty when there is none. */
    public String deadline;
    public String format;
    public int ptsWin;
    public int ptsDraw;
    public int ptsLoss;
    /** How matches of this category are scored, see SportTemplate.ENGINE_*. */
    public String engine;
    /**
     * This category's own copy of its rules, as "name=value" lines (see RuleSet).
     * Empty for a category made before templates existed.
     */
    public String rules;

    public RuleSet ruleSet() {
        return RuleSet.parse(rules);
    }

    public boolean isTeam() {
        return TYPE_TEAM.equals(type);
    }

    public boolean isKnockout() {
        return FORMAT_KNOCKOUT.equals(format);
    }

    public String typeLabel() {
        return isTeam() ? "Team" : "Individual";
    }

    public String formatLabel() {
        // Races and throws are neither: everyone competes at once, in heats and a final.
        if (SportTemplate.ENGINE_TIMED.equals(engine)) {
            return "Race";
        }
        if (SportTemplate.ENGINE_MEASURED.equals(engine)) {
            return "Field event";
        }
        return isKnockout() ? "Knockout" : "League";
    }

    /** Short description of the size rule, for example "7 to 10 players". */
    public String sizeText() {
        if (!isTeam()) {
            return "1 participant";
        }
        if (minSize == maxSize) {
            return minSize + " players";
        }
        return minSize + " to " + maxSize + " players";
    }
}
