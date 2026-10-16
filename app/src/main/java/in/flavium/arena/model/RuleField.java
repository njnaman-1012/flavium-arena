package in.flavium.arena.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One question on the category form, described by the sport's template.
 * The form is built from a list of these, so a new sport needs data, not new screens.
 */
public class RuleField {

    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_CHOICE = "choice";
    public static final String TYPE_TOGGLE = "toggle";
    public static final String TYPE_TEXT = "text";
    /** A value the organiser cannot change: saved with the rules but not shown. */
    public static final String TYPE_FIXED = "fixed";

    public String key;
    public String label;
    public String type;
    public String defaultValue = "";
    public String hint = "";
    /** For a choice: the options to pick from. */
    public List<String> options = new ArrayList<>();
    /** For a number: the allowed range. */
    public int min;
    public int max = Integer.MAX_VALUE;
    /** When set, the field is only shown while that other field has one of these values. */
    public String showIfKey;
    public List<String> showIfValues = new ArrayList<>();

    public boolean isFixed() {
        return TYPE_FIXED.equals(type);
    }

    /**
     * Should this field be on the form, given the answers so far?
     *
     * A field with a condition is shown only when the field it depends on has one of the
     * wanted answers AND is itself shown. Example: "finishers who qualify" depends on
     * "run heats"; for a throwing event "run heats" is hidden, so its dependants are too.
     */
    public boolean isShownFor(RuleSet values, List<RuleField> allFields) {
        if (isFixed()) {
            return false;
        }
        if (showIfKey == null) {
            return true;
        }
        if (!showIfValues.contains(values.get(showIfKey, ""))) {
            return false;
        }
        for (RuleField other : allFields) {
            if (other.key.equals(showIfKey)) {
                return other.isFixed() || other.isShownFor(values, allFields);
            }
        }
        return true;
    }

    /**
     * Checks one answer. Returns null when it is acceptable, otherwise a message.
     */
    public String problemWith(String value) {
        if (TYPE_NUMBER.equals(type)) {
            int number;
            try {
                number = Integer.parseInt(value == null ? "" : value.trim());
            } catch (NumberFormatException e) {
                return "\"" + label + "\" must be a whole number.";
            }
            if (number < min || number > max) {
                return "\"" + label + "\" must be between " + min + " and " + max + ".";
            }
        } else if (TYPE_CHOICE.equals(type)) {
            if (!options.contains(value)) {
                return "Choose one of the options for \"" + label + "\".";
            }
        } else if (TYPE_TOGGLE.equals(type)) {
            if (!RuleSet.YES.equals(value) && !RuleSet.NO.equals(value)) {
                return "\"" + label + "\" must be switched on or off.";
            }
        }
        return null;
    }
}
