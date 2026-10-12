package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.DbHelper;
import com.example.flaviumarena.data.DevData;
import com.example.flaviumarena.data.PermissionDeniedException;
import com.example.flaviumarena.data.SqliteArenaRepository;
import com.example.flaviumarena.model.AdminAudit;
import com.example.flaviumarena.model.AppSettings;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.util.DateUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Coach administers the sports Faculty assigned to them and nothing else. The Coach of
 * these tests looks after Football; Badminton and Cricket are other people's sports.
 * Uses a temporary in-memory database.
 */
@RunWith(AndroidJUnit4.class)
public class CoachInstrumentedTest {

    private static final String PASSWORD = "Temp@12345";

    private DbHelper helper;
    private ArenaRepository repo;
    private User faculty, core, coach;
    private Sport football, badminton;
    private Category sevens, singles;
    private List<Branch> branches;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null, true);
        repo = new SqliteArenaRepository(helper);
        faculty = repo.createFirstFaculty("Test Faculty", "faculty@flavium.test", PASSWORD,
                "faculty@flavium.test");
        core = repo.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE);
        branches = repo.getBranches();
        for (Sport s : repo.getSports()) {
            if (s.name.equals("Football")) {
                football = s;
            } else if (s.name.equals("Badminton")) {
                badminton = s;
            }
        }
        sevens = repo.getCategories(football.id).get(0);
        singles = repo.getCategories(badminton.id).get(0);

        String id = repo.createUser(faculty, "Football Coach", "coach@flavium.test", PASSWORD,
                Role.COACH, null);
        repo.setCoachSports(faculty, id, Arrays.asList(football.id));
        coach = repo.login("coach@flavium.test", PASSWORD, Role.COACH);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private interface Action {
        void run();
    }

    private void denied(Action action) {
        try {
            action.run();
            fail("Expected PermissionDeniedException");
        } catch (ArenaException e) {
            assertTrue("Expected PermissionDeniedException but got " + e,
                    e instanceof PermissionDeniedException);
        }
    }

    private Category newCategory(String sportId, String templateKey, String name) {
        SportTemplate template = repo.getTemplate(templateKey);
        Category c = new Category();
        c.sportId = sportId;
        c.name = name;
        c.deadline = "";
        c.rules = template.defaults().toText();
        return c;
    }

    private Registration team(Category c, int branchIndex, int year) {
        Registration r = new Registration();
        r.categoryId = c.id;
        r.branchId = branches.get(branchIndex).id;
        r.year = year;
        r.contactName = "Contact";
        StringBuilder members = new StringBuilder();
        for (int i = 1; i <= c.minSize; i++) {
            members.append("Member ").append(i).append("\n");
        }
        r.members = members.toString();
        return r;
    }

    private Match matchOf(Category c, boolean wantFinal) {
        for (Match m : repo.getMatches(c.id)) {
            if (m.isFinal == wantFinal) {
                return m;
            }
        }
        throw new IllegalStateException("No such match in " + c.name);
    }

    // ----- Who can be a Coach -----

    @Test
    public void onlyFacultyCreatesCoachesAndAssignsSports() {
        assertEquals(Role.COACH, coach.role);
        assertEquals(Arrays.asList(football.id), coach.coachSportIds);
        assertEquals("Football", coach.coachSportNames);
        assertNull("A Coach has no branch", coach.branchId);

        // Core cannot create a Coach or change a Coach's sports.
        denied(() -> repo.createUser(core, "Another", "c2@flavium.test", PASSWORD, Role.COACH,
                null));
        denied(() -> repo.setCoachSports(core, coach.id, Arrays.asList(badminton.id)));
        // A Coach cannot give themselves more sports, or create or promote anyone.
        denied(() -> repo.setCoachSports(coach, coach.id,
                Arrays.asList(football.id, badminton.id)));
        denied(() -> repo.createUser(coach, "Friend", "friend@flavium.test", PASSWORD,
                Role.ASOO_CORE, null));
        denied(() -> repo.createUser(coach, "Friend", "friend@flavium.test", PASSWORD,
                Role.COACH, null));
        denied(() -> repo.updateUser(coach, coach.id, coach.name, coach.email, Role.FACULTY,
                null));
        denied(() -> repo.getUsers(coach));

        // The assignment is written to the administrative log.
        boolean logged = false;
        for (AdminAudit a : repo.getAdminAudit(faculty)) {
            logged = logged || "COACH_SPORTS".equals(a.action);
        }
        assertTrue(logged);
    }

    @Test
    public void coachHasNoGlobalAdministration() {
        Sport kabaddi = new Sport();
        kabaddi.name = "Kabaddi";
        kabaddi.description = "";
        denied(() -> repo.saveSport(coach, kabaddi));
        denied(() -> repo.addSportFromTemplate(coach, "squash", null, null));
        denied(() -> repo.deleteSport(coach, football.id));
        Branch branch = new Branch();
        branch.name = "New Branch";
        branch.code = "NB";
        denied(() -> repo.saveBranch(coach, branch));
        denied(() -> repo.setBranchActive(coach, branches.get(0).id, false));
        denied(() -> repo.setFlag(coach, AppSettings.CORE_CAN_FINALISE, false));
        denied(() -> repo.getAdminAudit(coach));
        denied(() -> repo.getStats(coach));
    }

    // ----- Inside the assigned sport -----

    @Test
    public void coachManagesCategoriesRegistrationsAndFixturesOfTheirSport() {
        // Categories.
        String cup = repo.saveCategory(coach, newCategory(football.id, "football", "Coach Cup"));
        assertNotNull(repo.getCategory(cup));
        repo.deleteCategory(coach, cup);

        // Registrations: add for any branch, see the private details, approve.
        String teamId = repo.saveRegistration(coach, team(sevens, 0, 1));
        Registration seen = repo.getRegistration(coach, teamId);
        assertEquals(Registration.STATUS_PENDING, seen.status);
        repo.setRegistrationStatus(coach, teamId, Registration.STATUS_APPROVED);
        boolean phones = false;
        boolean pending = false;
        for (Registration r : repo.getRegistrations(coach, sevens.id)) {
            phones = phones || !r.contactPhone.isEmpty();
            pending = pending || Registration.STATUS_PENDING.equals(r.status);
        }
        assertTrue("A Coach sees phone numbers in their sport", phones);
        assertTrue("A Coach sees pending entries in their sport", pending);

        // Fixtures and results, including making a result official.
        Match live = matchOf(sevens, false);
        repo.setMatchStatus(coach, live.id, repo.getMatch(live.id).version, Match.STATUS_LIVE);
        repo.updateScore(coach, live.id, repo.getMatch(live.id).version, 2, 0, "",
                Match.STATUS_COMPLETED);
        repo.finaliseMatch(coach, live.id, repo.getMatch(live.id).version);
        assertTrue(repo.getMatch(live.id).isFinal);
        repo.reopenMatch(coach, live.id, repo.getMatch(live.id).version, "Coach review");
        assertFalse(repo.getMatch(live.id).isFinal);
        assertFalse(repo.getScoreAudit(coach, live.id).isEmpty());

        Match fixture = new Match();
        fixture.categoryId = sevens.id;
        fixture.teamAId = live.teamAId;
        fixture.teamBId = live.teamBId;
        fixture.date = DateUtil.today();
        fixture.time = "18:00";
        fixture.venue = "Ground";
        fixture.round = "Friendly";
        String added = repo.saveFixture(coach, fixture);
        repo.deleteFixture(coach, added);
        assertFalse(repo.getAsooCores(coach).isEmpty());
    }

    // ----- Outside it -----

    @Test
    public void coachCannotAdministerAnotherSport() {
        denied(() -> repo.saveCategory(coach,
                newCategory(badminton.id, "badminton", "Coach Singles")));
        denied(() -> repo.deleteCategory(coach, singles.id));
        // Editing an existing category of another sport, even when the form claims it
        // belongs to Football.
        Category forged = repo.getCategory(singles.id);
        forged.sportId = football.id;
        denied(() -> repo.saveCategory(coach, forged));

        Registration pendingOther = null;
        for (Registration r : repo.getRegistrations(core, singles.id)) {
            if (Registration.STATUS_PENDING.equals(r.status)) {
                pendingOther = r;
            }
        }
        assertNotNull(pendingOther);
        String otherId = pendingOther.id;
        denied(() -> repo.setRegistrationStatus(coach, otherId,
                Registration.STATUS_APPROVED));
        denied(() -> repo.deleteRegistration(coach, otherId));
        // In another sport a Coach sees what the public sees.
        assertNull(repo.getRegistration(coach, otherId));
        for (Registration r : repo.getRegistrations(coach, singles.id)) {
            assertTrue(r.isApproved());
            assertEquals("", r.contactPhone);
        }

        Match other = matchOf(singles, false);
        denied(() -> repo.setMatchStatus(coach, other.id, other.version,
                Match.STATUS_COMPLETED));
        denied(() -> repo.updateScore(coach, other.id, other.version, 1, 0, "",
                Match.STATUS_LIVE));
        denied(() -> repo.finaliseMatch(coach, other.id, other.version));
        denied(() -> repo.deleteFixture(coach, other.id));
        denied(() -> repo.getScoreAudit(coach, other.id));
        // A fixture of another sport cannot be claimed by sending a Football category.
        Match claimed = repo.getMatch(other.id);
        claimed.categoryId = sevens.id;
        denied(() -> repo.saveFixture(coach, claimed));
    }

    @Test
    public void changingTheAssignmentTakesEffectAtOnce() {
        // Faculty moves the Coach from Football to Badminton.
        repo.setCoachSports(faculty, coach.id, Arrays.asList(badminton.id));
        // The Coach's phone still holds the old object; the repository reads it again.
        denied(() -> repo.saveCategory(coach, newCategory(football.id, "football", "Late Cup")));
        repo.deleteCategory(coach, repo.saveCategory(coach,
                newCategory(badminton.id, "badminton", "New Singles")));

        // A screen cannot grant itself a sport by editing its own object.
        coach.coachSportIds = new ArrayList<>(Arrays.asList(football.id, badminton.id));
        coach.sportAdmin = true;
        coach.role = Role.FACULTY;
        denied(() -> repo.saveCategory(coach, newCategory(football.id, "football", "Forged")));

        // A Coach who becomes something else keeps no sports; a deactivated one has none.
        repo.updateUser(faculty, coach.id, coach.name, "coach@flavium.test", Role.ASOO_CORE,
                null);
        assertTrue(repo.getUser(coach.id).coachSportIds.isEmpty());
    }
}
