package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import in.flavium.arena.data.ArenaException;
import in.flavium.arena.data.ArenaRepository;
import in.flavium.arena.data.DbHelper;
import in.flavium.arena.data.DevData;
import in.flavium.arena.data.PermissionDeniedException;
import in.flavium.arena.data.SqliteArenaRepository;
import in.flavium.arena.data.ValidationException;
import in.flavium.arena.model.Branch;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.Role;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.SportTemplate;
import in.flavium.arena.model.StandingRow;
import in.flavium.arena.model.User;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * One Branch CR per branch, and the registration limits of a category: one team per
 * branch and year unless the category allows more, labels for extra teams, capacity.
 * Uses a temporary in-memory database.
 */
@RunWith(AndroidJUnit4.class)
public class RegistrationRulesInstrumentedTest {

    private static final String PASSWORD = "Temp@12345";

    private DbHelper helper;
    private ArenaRepository repo;
    private User faculty, core, cr;
    private List<Branch> branches;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null, true);
        repo = new SqliteArenaRepository(helper);
        faculty = repo.createFirstFaculty("Test Faculty", "faculty@flavium.test", PASSWORD,
                "faculty@flavium.test");
        core = repo.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE);
        cr = repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CAPTAIN);
        branches = repo.getBranches();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private interface Action {
        void run();
    }

    private void refused(Class<? extends ArenaException> kind, Action action) {
        try {
            action.run();
            fail("Expected " + kind.getSimpleName());
        } catch (ArenaException e) {
            assertTrue("Expected " + kind.getSimpleName() + " but got " + e, kind.isInstance(e));
        }
    }

    private Category category(String templateKey, String name, String... changes) {
        SportTemplate template = repo.getTemplate(templateKey);
        String sportId = null;
        for (Sport s : repo.getSports()) {
            if (s.name.equals(template.name)) {
                sportId = s.id;
            }
        }
        if (sportId == null) {
            sportId = repo.addSportFromTemplate(core, templateKey, null, null);
        }
        RuleSet rules = template.defaults();
        for (int i = 0; i < changes.length; i += 2) {
            rules.put(changes[i], changes[i + 1]);
        }
        Category c = new Category();
        c.sportId = sportId;
        c.name = name;
        c.deadline = "";
        c.rules = rules.toText();
        return repo.getCategory(repo.saveCategory(core, c));
    }

    private Registration entry(Category c, String branchId, int year, String label,
                               String person) {
        Registration r = new Registration();
        r.categoryId = c.id;
        r.branchId = branchId;
        r.year = year;
        r.teamLabel = label;
        r.name = person;
        r.contactName = "Contact";
        StringBuilder members = new StringBuilder();
        for (int i = 1; c.isTeam() && i <= c.minSize; i++) {
            members.append("Member ").append(i).append("\n");
        }
        r.members = members.toString();
        return r;
    }

    private String otherBranch() {
        for (Branch b : branches) {
            if (b.active && !b.id.equals(cr.branchId)) {
                return b.id;
            }
        }
        throw new IllegalStateException("No second branch");
    }

    // ----- Registrations -----

    @Test
    public void oneTeamPerBranchAndYearUnlessTheCategoryAllowsMore() {
        Category single = category("volleyball", "Single Team Cup");
        assertEquals(1, single.ruleSet().getInt(RuleSet.TEAMS_PER_BRANCH, 0));
        String first = repo.saveRegistration(cr, entry(single, cr.branchId, 3, "", null));
        // A second team for the same branch and year is refused, with or without a label.
        refused(ValidationException.class, () ->
                repo.saveRegistration(cr, entry(single, cr.branchId, 3, "", null)));
        refused(ValidationException.class, () ->
                repo.saveRegistration(cr, entry(single, cr.branchId, 3, "Team B", null)));
        // Another year of the same branch is a different team.
        repo.saveRegistration(cr, entry(single, cr.branchId, 2, "", null));
        // The team is named after its branch and year.
        assertEquals(cr.branchName + " — Year 3",
                repo.getRegistration(core, first).name);
        // A rejected team does not block a new one.
        repo.setRegistrationStatus(core, first, Registration.STATUS_REJECTED);
        repo.saveRegistration(core, entry(single, cr.branchId, 3, "Reserves", null));

        Category two = category("volleyball", "Two Team Cup", RuleSet.TEAMS_PER_BRANCH, "2");
        repo.saveRegistration(cr, entry(two, cr.branchId, 3, "", null));
        // The extra team needs a label of its own.
        refused(ValidationException.class, () ->
                repo.saveRegistration(cr, entry(two, cr.branchId, 3, "", null)));
        String second = repo.saveRegistration(cr, entry(two, cr.branchId, 3, "Team B", null));
        assertEquals(cr.branchName + " — Year 3 (Team B)",
                repo.getRegistration(core, second).name);
        refused(ValidationException.class, () ->
                repo.saveRegistration(cr, entry(two, cr.branchId, 3, "Team C", null)));
    }

    @Test
    public void branchCrRegistersOnlyTheirOwnBranch() {
        Category c = category("volleyball", "Own Branch Cup");
        String other = otherBranch();
        refused(PermissionDeniedException.class, () ->
                repo.saveRegistration(cr, entry(c, other, 3, "", null)));
        // Core registers the other branch; the Branch CR cannot take that entry over
        // by editing it into their own branch, nor see its private details.
        String theirs = repo.saveRegistration(core, entry(c, other, 3, "", null));
        Registration moved = entry(c, cr.branchId, 3, "", null);
        moved.id = theirs;
        refused(PermissionDeniedException.class, () -> repo.saveRegistration(cr, moved));
        refused(PermissionDeniedException.class, () -> repo.deleteRegistration(cr, theirs));
        refused(PermissionDeniedException.class, () ->
                repo.setRegistrationStatus(cr, theirs, Registration.STATUS_APPROVED));
        // A member of the public cannot register at all.
        refused(PermissionDeniedException.class, () ->
                repo.saveRegistration(null, entry(c, other, 2, "", null)));
    }

    @Test
    public void categoryCapacityAndTeamSizeAreEnforced() {
        Category c = category("volleyball", "Small Cup", RuleSet.MAX_ENTRIES, "2");
        repo.saveRegistration(core, entry(c, branches.get(0).id, 1, "", null));
        String second = repo.saveRegistration(core, entry(c, branches.get(1).id, 1, "", null));
        refused(ValidationException.class, () ->
                repo.saveRegistration(core, entry(c, branches.get(2).id, 1, "", null)));
        // A place becomes free when an entry is rejected.
        repo.setRegistrationStatus(core, second, Registration.STATUS_REJECTED);
        repo.saveRegistration(core, entry(c, branches.get(2).id, 1, "", null));

        // Too few players for the category.
        Category sized = category("volleyball", "Sized Cup");
        Registration small = entry(sized, branches.get(0).id, 1, "", null);
        small.members = "Only One";
        refused(ValidationException.class, () -> repo.saveRegistration(core, small));
        // A deadline in the past closes registration.
        Category closed = category("volleyball", "Closed Cup");
        closed.deadline = "2020-01-01";
        repo.saveCategory(core, closed);
        refused(ValidationException.class, () ->
                repo.saveRegistration(core, entry(closed, branches.get(0).id, 1, "", null)));
    }

    @Test
    public void individualsAreShownWithTheirBranchAndYear() {
        Category chess = category("chess", "Open Chess");
        String id = repo.saveRegistration(cr, entry(chess, cr.branchId, 2, "", "Asha Rao"));
        // The same person cannot be entered twice.
        refused(ValidationException.class, () ->
                repo.saveRegistration(cr, entry(chess, cr.branchId, 2, "", "Asha Rao")));
        repo.setRegistrationStatus(core, id, Registration.STATUS_APPROVED);
        StandingRow row = repo.getStandings(chess.id).get(0);
        assertEquals("Asha Rao", row.name);
        assertEquals(cr.branchName + " — Year 2", row.detail);
    }

    // ----- One Branch CR per branch -----

    @Test
    public void aBranchHasOneActiveBranchCrAndCanGetAReplacement() {
        refused(ValidationException.class, () -> repo.createUser(faculty, "Second CR",
                "cr2@flavium.test", PASSWORD, Role.CAPTAIN, cr.branchId));
        // A Branch CR cannot appoint anyone, or move to another branch.
        refused(PermissionDeniedException.class, () -> repo.createUser(cr, "Friend",
                "friend@flavium.test", PASSWORD, Role.CAPTAIN, otherBranch()));
        refused(PermissionDeniedException.class, () -> repo.updateUser(cr, cr.id, cr.name,
                cr.email, Role.CAPTAIN, otherBranch()));

        // Registrations of the branch survive a change of Branch CR.
        Category c = category("volleyball", "Handover Cup");
        String team = repo.saveRegistration(cr, entry(c, cr.branchId, 3, "", null));
        repo.setUserActive(faculty, cr.id, false);
        String replacementId = repo.createUser(faculty, "New CR", "cr2@flavium.test", PASSWORD,
                Role.CAPTAIN, cr.branchId);
        User replacement = repo.login("cr2@flavium.test", PASSWORD, Role.CAPTAIN);
        assertEquals(replacementId, replacement.id);
        assertEquals(team, repo.getRegistration(replacement, team).id);
        // The old account cannot come back while the new one is active.
        refused(ValidationException.class, () -> repo.setUserActive(faculty, cr.id, true));
    }

    @Test
    public void theDatabaseItselfRefusesASecondActiveBranchCr() {
        ContentValues second = new ContentValues();
        second.put("id", "second-cr");
        second.put("name", "Second CR");
        second.put("email", "direct@flavium.test");
        second.put("salt", "x");
        second.put("password_hash", "x");
        second.put("role", Role.CAPTAIN);
        second.put("active", 1);
        second.put("branch_id", cr.branchId);
        try {
            helper.getWritableDatabase().insertOrThrow("users", null, second);
            fail("The unique index should have refused this row");
        } catch (SQLiteConstraintException expected) {
            // correct
        }
    }
}
