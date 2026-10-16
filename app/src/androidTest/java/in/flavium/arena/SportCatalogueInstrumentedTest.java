package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import in.flavium.arena.data.ArenaRepository;
import in.flavium.arena.data.DbHelper;
import in.flavium.arena.data.SqliteArenaRepository;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.RuleField;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.SportTemplate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the real sport catalogue (assets/sport_templates.json) into a temporary database
 * and checks every template in it, so a mistake in the catalogue is caught here and not
 * by an organiser in the middle of setting up a category.
 */
@RunWith(AndroidJUnit4.class)
public class SportCatalogueInstrumentedTest {

    private DbHelper helper;
    private ArenaRepository repo;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null, true);
        repo = new SqliteArenaRepository(helper);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private SportTemplate.Preset preset(SportTemplate t, String name) {
        for (SportTemplate.Preset p : t.presets) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }

    @Test
    public void catalogueContainsEveryRequestedSport() {
        Set<String> names = new HashSet<>();
        for (SportTemplate t : repo.getTemplates()) {
            names.add(t.name);
        }
        for (String wanted : Arrays.asList("Cricket", "Badminton", "Swimming", "8-Ball Pool",
                "Table Tennis", "Carrom", "Chess", "Athletics", "Football", "Volleyball",
                "Basketball", "Squash", "Tug of War", "Lawn Tennis", "Custom sport")) {
            assertTrue("Missing from the catalogue: " + wanted, names.contains(wanted));
        }
    }

    @Test
    public void everyTemplateAndPresetIsValidAgainstItsOwnRules() {
        Set<String> engines = new HashSet<>(Arrays.asList(SportTemplate.ENGINES));
        for (SportTemplate t : repo.getTemplates()) {
            assertTrue(t.name + ": unknown engine " + t.engine, engines.contains(t.engine));
            assertFalse(t.name + ": no source note", t.source.isEmpty());

            // Field keys are unique, choices have options, conditions point at real fields.
            Set<String> keys = new HashSet<>();
            for (RuleField f : t.fields) {
                assertTrue(t.name + ": duplicate field " + f.key, keys.add(f.key));
            }
            for (RuleField f : t.fields) {
                if (RuleField.TYPE_CHOICE.equals(f.type)) {
                    assertFalse(t.name + "." + f.key + " has no options", f.options.isEmpty());
                }
                if (f.showIfKey != null) {
                    assertTrue(t.name + "." + f.key + " depends on unknown " + f.showIfKey,
                            keys.contains(f.showIfKey));
                }
            }

            // The defaults, and every preset on top of them, must pass validation.
            assertNull(t.name + " defaults", t.problemWith(t.defaults()));
            for (SportTemplate.Preset p : t.presets) {
                RuleSet values = t.valuesFor(p);
                assertNull(t.name + " / " + p.name, t.problemWith(values));
                assertTrue(t.name + " / " + p.name + ": unknown engine",
                        engines.contains(t.engineFor(values)));
                for (String key : p.values.keySet()) {
                    assertTrue(t.name + " / " + p.name + " sets unknown value " + key,
                            keys.contains(key) || RuleSet.ENGINE.equals(key));
                }
            }
        }
    }

    @Test
    public void eachSportUsesTheRightKindOfScoring() {
        assertEquals(SportTemplate.ENGINE_SETS, repo.getTemplate("volleyball").engine);
        assertEquals(SportTemplate.ENGINE_SETS, repo.getTemplate("badminton").engine);
        assertEquals(SportTemplate.ENGINE_SETS, repo.getTemplate("table_tennis").engine);
        assertEquals(SportTemplate.ENGINE_SETS, repo.getTemplate("lawn_tennis").engine);
        assertEquals(SportTemplate.ENGINE_SCORE, repo.getTemplate("football").engine);
        assertEquals(SportTemplate.ENGINE_SCORE, repo.getTemplate("basketball").engine);
        assertEquals(SportTemplate.ENGINE_CRICKET, repo.getTemplate("cricket").engine);
        assertEquals(SportTemplate.ENGINE_RESULT, repo.getTemplate("chess").engine);
        assertEquals(SportTemplate.ENGINE_TIMED, repo.getTemplate("swimming").engine);

        // Athletics holds races and throws; the preset decides which.
        SportTemplate athletics = repo.getTemplate("athletics");
        for (String race : Arrays.asList("100 m", "200 m", "400 m", "800 m", "1500 m",
                "4 x 100 m Relay")) {
            assertNotNull("Missing athletics event " + race, preset(athletics, race));
            assertEquals(race, SportTemplate.ENGINE_TIMED,
                    athletics.engineFor(athletics.valuesFor(preset(athletics, race))));
        }
        for (String thrown : Arrays.asList("Discus Throw", "Shot Put")) {
            assertNotNull("Missing athletics event " + thrown, preset(athletics, thrown));
            assertEquals(thrown, SportTemplate.ENGINE_MEASURED,
                    athletics.engineFor(athletics.valuesFor(preset(athletics, thrown))));
        }
        for (String category : Arrays.asList("Men's Singles", "Women's Singles",
                "Men's Doubles", "Women's Doubles", "Mixed Doubles")) {
            assertNotNull(preset(repo.getTemplate("lawn_tennis"), category));
        }
    }

    @Test
    public void formsShowOnlyTheQuestionsOfTheirSport() {
        SportTemplate swimming = repo.getTemplate("swimming");
        SportTemplate volleyball = repo.getTemplate("volleyball");
        SportTemplate badminton = repo.getTemplate("badminton");
        assertNull("Swimming must not ask about sets", swimming.field("sets_best_of"));
        assertNotNull(swimming.field("distance_m"));
        assertNotNull(swimming.field("stroke"));
        assertNull("Volleyball must not ask about distance", volleyball.field("distance_m"));
        assertNotNull(volleyball.field("sets_best_of"));
        assertNull("Badminton must not ask about overs", badminton.field("overs"));
        assertNotNull(repo.getTemplate("cricket").field("overs"));

        // In athletics, the throwing questions appear only for a throw.
        SportTemplate athletics = repo.getTemplate("athletics");
        RuleSet race = athletics.valuesFor(preset(athletics, "100 m"));
        RuleSet thrown = athletics.valuesFor(preset(athletics, "Shot Put"));
        assertTrue(athletics.field("lanes").isShownFor(race, athletics.fields));
        assertFalse(athletics.field("attempts").isShownFor(race, athletics.fields));
        assertTrue(athletics.field("attempts").isShownFor(thrown, athletics.fields));
        assertFalse(athletics.field("lanes").isShownFor(thrown, athletics.fields));
        // Heat questions depend on "run heats", which a throw does not have.
        assertFalse(athletics.field("heats").isShownFor(thrown, athletics.fields));
        assertFalse(athletics.field("qualify_per_heat").isShownFor(thrown, athletics.fields));
        assertFalse(athletics.field("fastest_losers").isShownFor(thrown, athletics.fields));
        assertTrue(athletics.field("qualify_per_heat").isShownFor(race, athletics.fields));
        // A 1500 m race has no heats, so it does not ask about qualifiers either.
        RuleSet longRace = athletics.valuesFor(preset(athletics, "1500 m"));
        assertFalse(athletics.field("qualify_per_heat").isShownFor(longRace, athletics.fields));
    }

    @Test
    public void developmentCategoriesReceiveRulesAndEngines() {
        // The sample data is created without rules; DbHelper then fills them in.
        for (Sport sport : repo.getSports()) {
            for (Category c : repo.getCategories(sport.id)) {
                assertFalse(sport.name + " / " + c.name + " has no rules", c.rules.isEmpty());
                SportTemplate template = repo.getTemplate(sport.templateKey);
                assertNotNull(sport.name + " is not linked to a template", template);
                assertNull(template.problemWith(c.ruleSet()));
            }
        }
        Sport badminton = null;
        Sport football = null;
        List<Sport> sports = repo.getSports();
        for (Sport s : sports) {
            if (s.name.equals("Badminton")) {
                badminton = s;
            } else if (s.name.equals("Football")) {
                football = s;
            }
        }
        assertEquals("badminton", badminton.templateKey);
        Category singles = repo.getCategories(badminton.id).get(0);
        assertEquals(SportTemplate.ENGINE_SETS, singles.engine);
        assertEquals(Category.TYPE_INDIVIDUAL, singles.type);
        assertEquals("Singles", singles.ruleSet().get(RuleSet.PARTICIPATION, ""));
        assertEquals(SportTemplate.ENGINE_SCORE, repo.getCategories(football.id).get(0).engine);
    }
}
