package in.flavium.arena.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rules of one category: a list of named values such as
 *
 *     sets_best_of=3
 *     set_target=15
 *     set_win_by=2
 *
 * The values come from the form that is built from the sport's template. They are saved
 * with the category as plain text, one "name=value" per line, so a competition keeps
 * its own copy and later changes to a template cannot alter it.
 */
public class RuleSet {

    // Names that the rest of the app understands directly.
    public static final String ENGINE = "engine";
    public static final String PARTICIPATION = "participation";
    public static final String TEAM_MIN = "team_min";
    public static final String TEAM_MAX = "team_max";
    public static final String FORMAT = "format";
    /** How many teams one branch may enter for the same academic year. */
    public static final String TEAMS_PER_BRANCH = "teams_per_branch";
    /** Most entries the category accepts; 0 means no limit. */
    public static final String MAX_ENTRIES = "max_entries";
    public static final String PTS_WIN = "pts_win";
    public static final String PTS_DRAW = "pts_draw";
    public static final String PTS_LOSS = "pts_loss";

    public static final String YES = "yes";
    public static final String NO = "no";

    private final Map<String, String> values = new LinkedHashMap<>();

    public RuleSet() {
    }

    /** Reads the text form. Lines without "=" are ignored. */
    public static RuleSet parse(String text) {
        RuleSet rules = new RuleSet();
        if (text == null) {
            return rules;
        }
        for (String line : text.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                rules.values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return rules;
    }

    /** The text form that is stored in the database. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    public void put(String key, String value) {
        // A value must stay on one line, or it would break the text form.
        values.put(key, value == null ? "" : value.replace("\n", " ").trim());
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public String get(String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(values.get(key));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean isYes(String key) {
        return YES.equals(values.get(key));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Map<String, String> asMap() {
        return values;
    }

    // ----- Meaning of the common values -----

    /** "Individual" and "Singles" are one person; everything else is a team of some size. */
    public boolean isIndividual() {
        String participation = get(PARTICIPATION, "Team");
        return participation.equalsIgnoreCase("Individual") || participation.equalsIgnoreCase("Singles");
    }

    /** Only a format with "Knockout" in its name eliminates the loser. */
    public boolean isKnockout() {
        return get(FORMAT, "League").toLowerCase().contains("knockout");
    }
}
