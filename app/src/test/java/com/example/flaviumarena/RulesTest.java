package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.flaviumarena.data.CategoryRules;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.RuleField;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.SportTemplate;

import org.junit.Test;

import java.util.Arrays;

/** Rule values, template fields and how they map to a category. Runs on the computer. */
public class RulesTest {

    private static RuleField field(String key, String type, String defaultValue) {
        RuleField f = new RuleField();
        f.key = key;
        f.label = key;
        f.type = type;
        f.defaultValue = defaultValue;
        return f;
    }

    /** A small template shaped like the real volleyball one. */
    private static SportTemplate template() {
        SportTemplate t = new SportTemplate();
        t.key = "volleyball";
        t.name = "Volleyball";
        t.engine = SportTemplate.ENGINE_SETS;

        RuleField participation = field(RuleSet.PARTICIPATION, RuleField.TYPE_CHOICE, "Team");
        participation.options = Arrays.asList("Singles", "Team");
        RuleField teamMin = field(RuleSet.TEAM_MIN, RuleField.TYPE_NUMBER, "6");
        teamMin.min = 1;
        teamMin.max = 30;
        teamMin.showIfKey = RuleSet.PARTICIPATION;
        teamMin.showIfValues = Arrays.asList("Team");
        RuleField teamMax = field(RuleSet.TEAM_MAX, RuleField.TYPE_NUMBER, "12");
        teamMax.min = 1;
        teamMax.max = 30;
        RuleField format = field(RuleSet.FORMAT, RuleField.TYPE_CHOICE, "League");
        format.options = Arrays.asList("League", "Knockout");
        RuleField target = field("set_target", RuleField.TYPE_NUMBER, "25");
        target.min = 1;
        target.max = 99;
        RuleField deuce = field("deuce", RuleField.TYPE_TOGGLE, "yes");
        RuleField fixed = field("set_cap", RuleField.TYPE_FIXED, "0");
        t.fields.addAll(Arrays.asList(participation, teamMin, teamMax, format, target, deuce,
                fixed));

        SportTemplate.Preset shortFormat = new SportTemplate.Preset();
        shortFormat.name = "Short format";
        shortFormat.values.put("set_target", "15");
        t.presets.add(shortFormat);
        return t;
    }

    @Test
    public void rulesSurviveBeingSavedAsTextAndReadBack() {
        RuleSet rules = new RuleSet();
        rules.put("sets_best_of", "3");
        rules.put("note", "first line\nsecond line");
        rules.put("label", "a=b");

        RuleSet read = RuleSet.parse(rules.toText());
        assertEquals(3, read.getInt("sets_best_of", 0));
        assertEquals("first line second line", read.get("note", ""));
        assertEquals("a=b", read.get("label", ""));
        assertEquals(7, read.getInt("missing", 7));
        assertTrue(RuleSet.parse(null).isEmpty());
        assertTrue(RuleSet.parse("").isEmpty());
    }

    @Test
    public void participationAndFormatAreUnderstood() {
        RuleSet rules = new RuleSet();
        rules.put(RuleSet.PARTICIPATION, "Singles");
        rules.put(RuleSet.FORMAT, "Knockout");
        assertTrue(rules.isIndividual());
        assertTrue(rules.isKnockout());

        rules.put(RuleSet.PARTICIPATION, "Relay");
        rules.put(RuleSet.FORMAT, "Swiss");
        assertFalse(rules.isIndividual());
        assertFalse(rules.isKnockout());
    }

    @Test
    public void presetValuesAreLaidOverTheDefaults() {
        SportTemplate t = template();
        assertEquals(25, t.defaults().getInt("set_target", 0));
        RuleSet values = t.valuesFor(t.presets.get(0));
        assertEquals(15, values.getInt("set_target", 0));
        assertEquals(6, values.getInt(RuleSet.TEAM_MIN, 0));   // untouched default
        assertEquals("0", values.get("set_cap", ""));          // fixed value is kept
    }

    @Test
    public void answersAreCheckedAgainstTheTemplate() {
        SportTemplate t = template();
        assertNull(t.problemWith(t.defaults()));

        RuleSet tooHigh = t.defaults();
        tooHigh.put("set_target", "500");
        assertNotNull(t.problemWith(tooHigh));

        RuleSet notANumber = t.defaults();
        notANumber.put("set_target", "many");
        assertNotNull(t.problemWith(notANumber));

        RuleSet unknownChoice = t.defaults();
        unknownChoice.put(RuleSet.FORMAT, "Lottery");
        assertNotNull(t.problemWith(unknownChoice));

        RuleSet badToggle = t.defaults();
        badToggle.put("deuce", "maybe");
        assertNotNull(t.problemWith(badToggle));
    }

    @Test
    public void hiddenQuestionsAreNotChecked() {
        SportTemplate t = template();
        RuleSet singles = t.defaults();
        singles.put(RuleSet.PARTICIPATION, "Singles");
        singles.put(RuleSet.TEAM_MIN, "not relevant");
        // team_min is only asked for teams, so its value does not matter here.
        assertFalse(t.field(RuleSet.TEAM_MIN).isShownFor(singles, t.fields));
        assertNull(t.problemWith(singles));
        // A question that depends on a hidden question is hidden as well.
        RuleField onlyForTeams = field("squad_note", RuleField.TYPE_TEXT, "");
        onlyForTeams.showIfKey = RuleSet.TEAM_MIN;
        onlyForTeams.showIfValues = Arrays.asList("not relevant");
        t.fields.add(onlyForTeams);
        assertFalse(onlyForTeams.isShownFor(singles, t.fields));
        // A fixed value is never shown on the form.
        assertFalse(t.field("set_cap").isShownFor(singles, t.fields));
    }

    @Test
    public void engineComesFromTheRulesOrElseTheTemplate() {
        SportTemplate t = template();
        RuleSet rules = t.defaults();
        assertEquals(SportTemplate.ENGINE_SETS, t.engineFor(rules));
        rules.put(RuleSet.ENGINE, SportTemplate.ENGINE_MEASURED);
        assertEquals(SportTemplate.ENGINE_MEASURED, t.engineFor(rules));
        rules.put(RuleSet.ENGINE, "SOMETHING_ELSE");
        assertEquals(SportTemplate.ENGINE_SCORE, t.engineFor(rules));
    }

    @Test
    public void categoryColumnsFollowTheRules() {
        SportTemplate t = template();
        Category c = new Category();

        RuleSet team = t.defaults();
        team.put(RuleSet.PTS_WIN, "3");
        CategoryRules.applyTo(c, t, team);
        assertEquals(Category.TYPE_TEAM, c.type);
        assertEquals(6, c.minSize);
        assertEquals(12, c.maxSize);
        assertEquals(Category.FORMAT_LEAGUE, c.format);
        assertEquals(3, c.ptsWin);
        assertEquals(SportTemplate.ENGINE_SETS, c.engine);

        RuleSet singles = t.defaults();
        singles.put(RuleSet.PARTICIPATION, "Singles");
        singles.put(RuleSet.FORMAT, "Knockout");
        CategoryRules.applyTo(c, t, singles);
        assertEquals(Category.TYPE_INDIVIDUAL, c.type);
        assertEquals(1, c.minSize);
        assertEquals(1, c.maxSize);
        assertEquals(Category.FORMAT_KNOCKOUT, c.format);
    }

    @Test
    public void anOldCategoryGetsRulesFromItsColumns() {
        SportTemplate t = template();
        Category old = new Category();
        old.type = Category.TYPE_INDIVIDUAL;
        old.minSize = 1;
        old.maxSize = 1;
        old.format = Category.FORMAT_KNOCKOUT;
        old.ptsWin = 2;

        RuleSet rules = CategoryRules.fromColumns(t, old);
        assertEquals("Singles", rules.get(RuleSet.PARTICIPATION, ""));
        assertEquals("Knockout", rules.get(RuleSet.FORMAT, ""));
        assertEquals(2, rules.getInt(RuleSet.PTS_WIN, 0));
        assertEquals(25, rules.getInt("set_target", 0)); // template default fills the gaps

        old.type = Category.TYPE_TEAM;
        old.minSize = 7;
        old.maxSize = 10;
        old.format = Category.FORMAT_LEAGUE;
        rules = CategoryRules.fromColumns(t, old);
        assertEquals("Team", rules.get(RuleSet.PARTICIPATION, ""));
        assertEquals(7, rules.getInt(RuleSet.TEAM_MIN, 0));
        assertEquals(10, rules.getInt(RuleSet.TEAM_MAX, 0));
        assertEquals("League", rules.get(RuleSet.FORMAT, ""));
    }
}
