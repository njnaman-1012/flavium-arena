package in.flavium.arena.data;

import in.flavium.arena.model.Category;
import in.flavium.arena.model.RuleField;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.SportTemplate;

/**
 * The link between a category's rule values and the few columns the rest of the app
 * has always used (individual or team, team size, league or knockout, league points).
 *
 * The rules are the source of truth. Whenever a category is saved, those columns are
 * worked out again from the rules, so the two can never disagree.
 */
public final class CategoryRules {

    private CategoryRules() {
    }

    /** Fills the classic columns and the scoring engine of a category from its rules. */
    public static void applyTo(Category category, SportTemplate template, RuleSet rules) {
        boolean individual = rules.isIndividual();
        category.type = individual ? Category.TYPE_INDIVIDUAL : Category.TYPE_TEAM;
        category.minSize = individual ? 1 : rules.getInt(RuleSet.TEAM_MIN, 1);
        category.maxSize = individual ? 1 : rules.getInt(RuleSet.TEAM_MAX, category.minSize);
        category.format = rules.isKnockout() ? Category.FORMAT_KNOCKOUT : Category.FORMAT_LEAGUE;
        category.ptsWin = rules.getInt(RuleSet.PTS_WIN, 0);
        category.ptsDraw = rules.getInt(RuleSet.PTS_DRAW, 0);
        category.ptsLoss = rules.getInt(RuleSet.PTS_LOSS, 0);
        category.engine = template.engineFor(rules);
    }

    /**
     * Rules for a category that was created before templates existed: the template's
     * defaults, with the values the category already has laid over them.
     */
    public static RuleSet fromColumns(SportTemplate template, Category category) {
        RuleSet rules = template.defaults();

        RuleField participation = template.field(RuleSet.PARTICIPATION);
        if (participation != null) {
            rules.put(RuleSet.PARTICIPATION,
                    pick(participation, !category.isTeam(), "Individual", "Singles", "Team"));
        }
        if (category.isTeam()) {
            rules.put(RuleSet.TEAM_MIN, String.valueOf(category.minSize));
            rules.put(RuleSet.TEAM_MAX, String.valueOf(category.maxSize));
        }
        RuleField format = template.field(RuleSet.FORMAT);
        if (format != null) {
            rules.put(RuleSet.FORMAT, pick(format, category.isKnockout(), "Knockout", "Knockout",
                    "League"));
        }
        rules.put(RuleSet.PTS_WIN, String.valueOf(category.ptsWin));
        rules.put(RuleSet.PTS_DRAW, String.valueOf(category.ptsDraw));
        rules.put(RuleSet.PTS_LOSS, String.valueOf(category.ptsLoss));
        return rules;
    }

    /**
     * Chooses the option of a choice field that matches a yes/no property.
     * "wanted" true: the first of the two preferred names that the field offers.
     * "wanted" false: the fallback name if offered, otherwise the first option that is
     * not one of the preferred names.
     */
    private static String pick(RuleField field, boolean wanted, String preferred,
                               String alsoPreferred, String fallback) {
        if (wanted) {
            for (String option : field.options) {
                if (option.equalsIgnoreCase(preferred) || option.equalsIgnoreCase(alsoPreferred)) {
                    return option;
                }
            }
        } else {
            if (field.options.contains(fallback)) {
                return fallback;
            }
            for (String option : field.options) {
                if (!option.equalsIgnoreCase(preferred) && !option.equalsIgnoreCase(alsoPreferred)) {
                    return option;
                }
            }
        }
        return field.defaultValue;
    }
}
