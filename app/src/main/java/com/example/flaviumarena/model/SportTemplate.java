package com.example.flaviumarena.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the app knows about a kind of sport: its name, how it is scored, the
 * questions to ask when a category is created, and ready-made categories ("presets").
 * Templates are data. They are loaded into the database from assets/sport_templates.json.
 */
public class SportTemplate {

    /** Key of the template used for sports that are not in the catalogue. */
    public static final String CUSTOM = "custom";

    // The six ways a competition can be scored.
    public static final String ENGINE_SCORE = "SCORE";       // one score each (football)
    public static final String ENGINE_SETS = "SETS";         // sets, games or frames
    public static final String ENGINE_CRICKET = "CRICKET";   // innings: runs, wickets, overs
    public static final String ENGINE_RESULT = "RESULT";     // win, draw or loss (chess)
    public static final String ENGINE_TIMED = "TIMED";       // race times, many entrants
    public static final String ENGINE_MEASURED = "MEASURED"; // distances with attempts
    public static final String[] ENGINES = {ENGINE_SCORE, ENGINE_SETS, ENGINE_CRICKET,
            ENGINE_RESULT, ENGINE_TIMED, ENGINE_MEASURED};

    /** A ready-made category, for example "Men's Singles" or "100 m". */
    public static class Preset {
        public String name;
        /** Values that differ from the template's defaults. */
        public Map<String, String> values = new LinkedHashMap<>();
    }

    public String key;
    public String name;
    public String description;
    /** Name of the picture, used as "ill_" + picture. */
    public String picture;
    /** Default scoring engine. A preset may name a different one. */
    public String engine;
    /** Where the default values come from and how far they can be trusted. */
    public String source;
    public List<RuleField> fields = new ArrayList<>();
    public List<Preset> presets = new ArrayList<>();

    public boolean isCustom() {
        return CUSTOM.equals(key);
    }

    public RuleField field(String fieldKey) {
        for (RuleField f : fields) {
            if (f.key.equals(fieldKey)) {
                return f;
            }
        }
        return null;
    }

    /** The template's own default answer to every question. */
    public RuleSet defaults() {
        RuleSet rules = new RuleSet();
        for (RuleField f : fields) {
            rules.put(f.key, f.defaultValue);
        }
        return rules;
    }

    /** Defaults with the values of one preset laid over them. */
    public RuleSet valuesFor(Preset preset) {
        RuleSet rules = defaults();
        if (preset != null) {
            for (Map.Entry<String, String> entry : preset.values.entrySet()) {
                rules.put(entry.getKey(), entry.getValue());
            }
        }
        return rules;
    }

    /** The engine that applies to a set of rules: their own "engine" value, else the template's. */
    public String engineFor(RuleSet rules) {
        String chosen = rules.get(RuleSet.ENGINE, engine);
        for (String known : ENGINES) {
            if (known.equals(chosen)) {
                return chosen;
            }
        }
        return ENGINE_SCORE;
    }

    /**
     * Checks a complete set of answers against the template. Only the questions that
     * are shown for these answers are checked. Returns null when everything is fine.
     */
    public String problemWith(RuleSet rules) {
        for (RuleField f : fields) {
            if (!f.isShownFor(rules, fields)) {
                continue;
            }
            String problem = f.problemWith(rules.get(f.key, ""));
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }
}
