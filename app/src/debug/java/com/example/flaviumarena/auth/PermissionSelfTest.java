package com.example.flaviumarena.auth;

import android.content.Context;

import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.ConflictException;
import com.example.flaviumarena.data.DbHelper;
import com.example.flaviumarena.data.PermissionDeniedException;
import com.example.flaviumarena.data.DevData;
import com.example.flaviumarena.data.SqliteArenaRepository;
import com.example.flaviumarena.data.ValidationException;
import com.example.flaviumarena.model.AppSettings;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.util.DateUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Calls the repository directly as the public and as each of the four roles, without
 * going through any screen, and reports whether every rule held. This proves the rules
 * are enforced by the data layer and not only by hidden buttons.
 *
 * This file lives in app/src/debug: the self-test is part of debug builds only.
 *
 * It runs on a temporary in-memory database filled with the development sample data,
 * so the real data on the device is never touched. The accounts and passwords used
 * here exist only inside that temporary database.
 */
public final class PermissionSelfTest {

    public static class Result {
        public final String name;
        public final boolean passed;
        public final String detail;

        Result(String name, boolean passed, String detail) {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
        }
    }

    // Used only inside the temporary database.
    private static final String FACULTY_EMAIL = "faculty@flavium.test";
    private static final String TEST_PASSWORD = "Temp@12345";

    private final List<Result> results = new ArrayList<>();
    private final ArenaRepository repo;
    /** Branch used for the sample participants created by the checks. */
    private String sampleBranchId;

    private PermissionSelfTest(ArenaRepository repo) {
        this.repo = repo;
    }

    public static List<Result> run(Context context) {
        DbHelper helper = new DbHelper(context.getApplicationContext(), null, true);
        PermissionSelfTest test = new PermissionSelfTest(new SqliteArenaRepository(helper));
        try {
            test.runAll();
        } catch (RuntimeException e) {
            test.results.add(new Result("Self-test stopped unexpectedly", false, e.toString()));
        } finally {
            helper.close();
        }
        return test.results;
    }

    private void runAll() {
        // ================= First Faculty account and login =================
        rejected("Faculty set-up is refused while no Faculty email is configured",
                ValidationException.class,
                () -> repo.createFirstFaculty("Someone", FACULTY_EMAIL, TEST_PASSWORD, ""));
        denied("Faculty set-up refuses an email that is not the authorised one",
                () -> repo.createFirstFaculty("Intruder", "intruder@flavium.test",
                        TEST_PASSWORD, FACULTY_EMAIL));
        allowed("The authorised email can set up the first Faculty account",
                () -> repo.createFirstFaculty("Test Faculty", FACULTY_EMAIL, TEST_PASSWORD,
                        FACULTY_EMAIL));
        denied("Set-up cannot be used again once a Faculty account exists",
                () -> repo.createFirstFaculty("Second", "second@flavium.test", TEST_PASSWORD,
                        "second@flavium.test"));

        denied("Choosing Faculty Login with a Core account is refused", () -> repo.login(
                DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.FACULTY));
        denied("Choosing Core Login with an Asoo Core account is refused", () -> repo.login(
                DevData.ASOO_EMAIL, DevData.ASOO_PASSWORD, Role.CORE));
        denied("Choosing Core Login with a Branch Captain account is refused",
                () -> repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CORE));
        rejected("A wrong password is refused", ValidationException.class,
                () -> repo.login(DevData.CORE_EMAIL, "not-the-password", Role.CORE));

        final User faculty = repo.login(FACULTY_EMAIL, TEST_PASSWORD, Role.FACULTY);
        final User core = repo.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE);
        final User asoo = repo.login(DevData.ASOO_EMAIL, DevData.ASOO_PASSWORD,
                Role.ASOO_CORE);
        final User captain = repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD,
                Role.CAPTAIN);

        // Sample records to test against.
        final Sport football = findSport("Football");
        final Category sevens = repo.getCategories(football.id).get(0);
        final Registration pending = findPending(core, sevens.id);
        Match finalFound = null;
        Match assignedFound = null;
        Match unassignedFound = null;
        for (Match m : repo.getMatches(sevens.id)) {
            if (m.isFinal) {
                finalFound = m;
            } else if (asoo.id.equals(m.assignedTo) && Match.STATUS_LIVE.equals(m.status)) {
                assignedFound = m;
            } else if (m.assignedTo == null) {
                unassignedFound = m;
            }
        }
        final Match finalMatch = finalFound;
        final Match assigned = assignedFound;
        final Match unassigned = unassignedFound;
        final Branch freeBranch = findBranchWithoutCaptain(captain);
        sampleBranchId = freeBranch.id;

        // ================= The public (no account) =================
        denied("Public cannot add a sport", () -> repo.saveSport(null, sport("Kabaddi")));
        denied("Public cannot add a category",
                () -> repo.saveCategory(null, category(football.id, "Public Cup")));
        denied("Public cannot approve a registration", () -> repo.setRegistrationStatus(
                null, pending.id, Registration.STATUS_APPROVED));
        denied("Public cannot create a fixture",
                () -> repo.saveFixture(null, copyAsNew(unassigned)));
        denied("Public cannot update a score", () -> repo.updateScore(null, assigned.id,
                assigned.version, 5, 5, "", Match.STATUS_LIVE));
        denied("Public cannot list accounts", () -> repo.getUsers(null));
        denied("Public cannot create an account", () -> repo.createUser(null, "X",
                "x@flavium.test", TEST_PASSWORD, Role.ASOO_CORE, null));
        denied("Public cannot read the score history",
                () -> repo.getScoreAudit(null, assigned.id));
        denied("Public cannot change administrative settings",
                () -> repo.setFlag(null, AppSettings.CORE_CAN_FINALISE, false));
        check("Public sees approved teams only, without phone numbers", () -> {
            List<Registration> list = repo.getRegistrations(null, sevens.id);
            if (list.isEmpty()) {
                throw new ValidationException("No registrations were returned.");
            }
            for (Registration r : list) {
                if (!r.isApproved() || !r.contactPhone.isEmpty()) {
                    throw new ValidationException("Private data was returned for " + r.name);
                }
            }
        });
        denied("A made-up user object that claims the Faculty role is refused", () -> {
            User fake = new User();
            fake.id = "no-such-user";
            fake.role = Role.FACULTY;
            repo.saveSport(fake, sport("Faked"));
        });

        // ================= Branch Captain =================
        check("A captain account carries its branch", () -> {
            if (!DevData.CAPTAIN_BRANCH.equals(captain.branchName)) {
                throw new ValidationException("Branch was " + captain.branchName);
            }
        });
        denied("Captain cannot add a sport", () -> repo.saveSport(captain, sport("Kabaddi")));
        denied("Captain cannot approve a registration", () -> repo.setRegistrationStatus(
                captain, pending.id, Registration.STATUS_APPROVED));
        denied("Captain cannot create a fixture",
                () -> repo.saveFixture(captain, copyAsNew(unassigned)));
        denied("Captain cannot update a score", () -> repo.updateScore(captain, assigned.id,
                assigned.version, 1, 1, "", Match.STATUS_LIVE));
        denied("Captain cannot list accounts", () -> repo.getUsers(captain));
        denied("Captain cannot move themselves to another branch", () -> repo.updateUser(
                captain, captain.id, captain.name, captain.email, Role.CAPTAIN, freeBranch.id));

        // ================= Branch Captain: own branch only =================
        final String[] team = new String[1];
        allowed("Captain can register a second team for their own branch", () -> team[0] =
                repo.saveRegistration(captain, teamEntry(sevens.id, captain.branchId, 3,
                        "Team B")));
        rejected("Two teams of one branch and year need different labels",
                ValidationException.class, () -> repo.saveRegistration(captain,
                        teamEntry(sevens.id, captain.branchId, 3, "")));
        denied("Captain cannot register a team for another branch",
                () -> repo.saveRegistration(captain,
                        teamEntry(sevens.id, freeBranch.id, 3, "")));
        check("The team name is built from branch, year and label", () -> {
            Registration saved = repo.getRegistration(captain, team[0]);
            String expected = DevData.CAPTAIN_BRANCH + " \u2014 Year 3 (Team B)";
            if (!expected.equals(saved.name)) {
                throw new ValidationException("Name was " + saved.name);
            }
            if (!Registration.STATUS_PENDING.equals(saved.status)) {
                throw new ValidationException("A captain's entry must start as PENDING.");
            }
        });
        check("The public cannot see a team that is waiting for approval", () -> {
            if (repo.getRegistration(null, team[0]) != null) {
                throw new ValidationException("The pending team was visible to the public.");
            }
        });
        check("Captain sees pending entries of their own branch but not of others", () -> {
            boolean own = false;
            for (Registration r : repo.getRegistrations(captain, sevens.id)) {
                own |= r.id.equals(team[0]);
                if (r.id.equals(pending.id)) {
                    throw new ValidationException("Another branch's pending entry was shown.");
                }
                if (!captain.branchId.equals(r.branchId) && !r.contactPhone.isEmpty()) {
                    throw new ValidationException("Another branch's phone number was shown.");
                }
            }
            if (!own) {
                throw new ValidationException("The captain's own pending team was missing.");
            }
        });
        denied("Captain cannot approve their own team", () -> repo.setRegistrationStatus(
                captain, team[0], Registration.STATUS_APPROVED));
        allowed("Captain can edit their team before it is approved", () -> {
            Registration mine = repo.getRegistration(captain, team[0]);
            mine.teamLabel = "Team C";
            repo.saveRegistration(captain, mine);
        });
        denied("Captain cannot edit another branch's registration",
                () -> repo.saveRegistration(captain, pending));
        denied("Captain cannot delete another branch's registration",
                () -> repo.deleteRegistration(captain, pending.id));
        denied("Captain cannot change another branch's logo",
                () -> repo.setRegistrationLogo(captain, pending.id, ""));
        allowed("Core approves the captain's team", () -> repo.setRegistrationStatus(core,
                team[0], Registration.STATUS_APPROVED));
        denied("Captain cannot edit a team after it has been approved", () -> {
            Registration mine = repo.getRegistration(captain, team[0]);
            mine.teamLabel = "Team D";
            repo.saveRegistration(captain, mine);
        });
        allowed("Captain can still change the logo of an approved team",
                () -> repo.setRegistrationLogo(captain, team[0], ""));
        rejected("The academic year must be 1 to 4", ValidationException.class,
                () -> repo.saveRegistration(core, teamEntry(sevens.id, freeBranch.id, 7, "")));

        // ================= Asoo Core =================
        denied("Asoo Core cannot add a sport", () -> repo.saveSport(asoo, sport("Kabaddi")));
        denied("Asoo Core cannot delete a sport", () -> repo.deleteSport(asoo, football.id));
        denied("Asoo Core cannot add a category",
                () -> repo.saveCategory(asoo, category(football.id, "Asoo Cup")));
        denied("Asoo Core cannot approve a registration", () -> repo.setRegistrationStatus(
                asoo, pending.id, Registration.STATUS_APPROVED));
        denied("Asoo Core cannot edit a registration",
                () -> repo.saveRegistration(asoo, pending));
        denied("Asoo Core cannot create a fixture",
                () -> repo.saveFixture(asoo, copyAsNew(unassigned)));
        denied("Asoo Core cannot delete a fixture",
                () -> repo.deleteFixture(asoo, assigned.id));
        denied("Asoo Core cannot create an account", () -> repo.createUser(asoo, "Friend",
                "friend@flavium.test", TEST_PASSWORD, Role.ASOO_CORE, null));
        denied("Asoo Core cannot promote themselves", () -> repo.updateUser(asoo, asoo.id,
                asoo.name, asoo.email, Role.CORE, null));
        denied("Asoo Core cannot update a match that is not assigned to them",
                () -> repo.updateScore(asoo, unassigned.id, unassigned.version, 1, 0, "",
                        Match.STATUS_LIVE));
        denied("Asoo Core cannot change a final result", () -> repo.updateScore(asoo,
                finalMatch.id, finalMatch.version, 9, 9, "", Match.STATUS_COMPLETED));
        allowed("Asoo Core can update the score of an assigned match", () -> repo.updateScore(
                asoo, assigned.id, assigned.version, 2, 0, "", Match.STATUS_LIVE));
        allowed("Asoo Core can mark an assigned match as completed", () -> repo.updateScore(
                asoo, assigned.id, repo.getMatch(assigned.id).version, 3, 1, "",
                Match.STATUS_COMPLETED));
        denied("Asoo Core cannot finalise a result", () -> repo.finaliseMatch(asoo, assigned.id,
                repo.getMatch(assigned.id).version));

        // ================= Core: festival administration =================
        final String[] ids = new String[4];
        allowed("Core can add a sport", () -> ids[0] = repo.saveSport(core, sport("Kabaddi")));
        allowed("Core can add a category",
                () -> ids[1] = repo.saveCategory(core, category(ids[0], "Open Singles")));
        allowed("Core can register two participants", () -> {
            ids[2] = repo.saveRegistration(core, participant(ids[1], "Player One"));
            ids[3] = repo.saveRegistration(core, participant(ids[1], "Player Two"));
        });
        rejected("Duplicate registration is rejected", ValidationException.class,
                () -> repo.saveRegistration(core, participant(ids[1], "player one")));
        rejected("Fixture between unapproved participants is rejected",
                ValidationException.class,
                () -> repo.saveFixture(core, fixture(ids[1], ids[2], ids[3], null)));
        allowed("Core can approve registrations", () -> {
            repo.setRegistrationStatus(core, ids[2], Registration.STATUS_APPROVED);
            repo.setRegistrationStatus(core, ids[3], Registration.STATUS_APPROVED);
        });
        final String[] matchId = new String[1];
        allowed("Core can create a fixture and assign an Asoo Core",
                () -> matchId[0] = repo.saveFixture(core, fixture(ids[1], ids[2], ids[3], asoo.id)));

        // ================= Sport catalogue and category rules =================
        final String[] catalogue = new String[2];
        denied("Captain cannot add a sport from the catalogue",
                () -> repo.addSportFromTemplate(captain, "volleyball", null, null));
        denied("Asoo Core cannot add a sport from the catalogue",
                () -> repo.addSportFromTemplate(asoo, "volleyball", null, null));
        allowed("Core can add a sport from the catalogue",
                () -> catalogue[0] = repo.addSportFromTemplate(core, "volleyball", null, null));
        rejected("The same catalogue sport cannot be added twice", ValidationException.class,
                () -> repo.addSportFromTemplate(core, "volleyball", null, null));
        rejected("A custom sport needs a name", ValidationException.class,
                () -> repo.addSportFromTemplate(core, "custom", "  ", null));
        allowed("Core can add a custom sport",
                () -> repo.addSportFromTemplate(core, "custom", "Kho-Kho", "Tag sport"));
        allowed("Core can add a category from the sport's template", () -> {
            SportTemplate t = repo.getTemplate("volleyball");
            Category c = new Category();
            c.sportId = catalogue[0];
            c.name = "Men's Team";
            c.deadline = "";
            c.rules = t.defaults().toText();
            catalogue[1] = repo.saveCategory(core, c);
        });
        check("The category's type, team size and scoring come from its rules", () -> {
            Category saved = repo.getCategory(catalogue[1]);
            if (!Category.TYPE_TEAM.equals(saved.type) || saved.minSize != 6
                    || !SportTemplate.ENGINE_SETS.equals(saved.engine)
                    || saved.ruleSet().getInt("set_target", 0) != 25) {
                throw new ValidationException("Saved as " + saved.type + ", size "
                        + saved.minSize + ", engine " + saved.engine);
            }
        });
        rejected("A rule value outside the template's range is refused",
                ValidationException.class, () -> {
                    Category c = repo.getCategory(catalogue[1]);
                    RuleSet rules = c.ruleSet();
                    rules.put("set_target", "500");
                    c.rules = rules.toText();
                    repo.saveCategory(core, c);
                });
        rejected("\"Best of\" an even number of sets is refused", ValidationException.class,
                () -> {
                    Category c = repo.getCategory(catalogue[1]);
                    RuleSet rules = c.ruleSet();
                    rules.put("sets_best_of", "4");
                    c.rules = rules.toText();
                    repo.saveCategory(core, c);
                });
        allowed("Core can change the rules before any match has started", () -> {
            Category c = repo.getCategory(catalogue[1]);
            RuleSet rules = c.ruleSet();
            rules.put("sets_best_of", "3");
            rules.put("set_target", "15");
            c.rules = rules.toText();
            repo.saveCategory(core, c);
        });
        check("Changing the template later does not change a saved category", () -> {
            // The category keeps its own copy: 15, not the template's 25.
            if (repo.getCategory(catalogue[1]).ruleSet().getInt("set_target", 0) != 15
                    || repo.getTemplate("volleyball").defaults().getInt("set_target", 0) != 25) {
                throw new ValidationException("The category did not keep its own rules.");
            }
        });
        rejected("Core cannot change rules once the category's matches have started",
                ValidationException.class, () -> {
                    // The football category has a live and a final match.
                    Category started = repo.getCategory(sevens.id);
                    RuleSet rules = started.ruleSet();
                    rules.put("period_minutes", "30");
                    started.rules = rules.toText();
                    repo.saveCategory(core, started);
                });
        allowed("Faculty can still change the rules of a started category", () -> {
            Category started = repo.getCategory(sevens.id);
            RuleSet rules = started.ruleSet();
            rules.put("period_minutes", "30");
            started.rules = rules.toText();
            repo.saveCategory(faculty, started);
        });

        // ================= Core: limits on accounts =================
        final String[] accounts = new String[4];
        allowed("Core can add an Asoo Core account", () -> accounts[0] = repo.createUser(core,
                "New Asoo", "newasoo@flavium.test", TEST_PASSWORD, Role.ASOO_CORE, null));
        denied("Core cannot create a Core account", () -> repo.createUser(core, "Extra Core",
                "extracore@flavium.test", TEST_PASSWORD, Role.CORE, null));
        denied("Core cannot create a Faculty account", () -> repo.createUser(core, "Fake",
                "fakefaculty@flavium.test", TEST_PASSWORD, Role.FACULTY, null));
        denied("Core cannot promote an Asoo Core to Core", () -> repo.updateUser(core,
                accounts[0], "New Asoo", "newasoo@flavium.test", Role.CORE, null));
        denied("Core cannot promote themselves to Faculty", () -> repo.updateUser(core, core.id,
                core.name, core.email, Role.FACULTY, null));
        denied("Core cannot edit a Faculty account", () -> repo.updateUser(core, faculty.id,
                "Renamed", faculty.email, Role.FACULTY, null));
        denied("Core cannot deactivate a Faculty account",
                () -> repo.setUserActive(core, faculty.id, false));
        denied("Core cannot reset a Faculty password",
                () -> repo.resetPassword(core, faculty.id, TEST_PASSWORD));
        denied("Core cannot change administrative settings",
                () -> repo.setFlag(core, AppSettings.CORE_CAN_FINALISE, true));
        denied("Core cannot read the administrative log", () -> repo.getAdminAudit(core));
        check("Core does not see Faculty or other Core accounts in the list", () -> {
            for (User u : repo.getUsers(core)) {
                if (!u.id.equals(core.id)
                        && (Role.FACULTY.equals(u.role) || Role.CORE.equals(u.role))) {
                    throw new ValidationException("The list included " + Role.label(u.role));
                }
            }
        });

        // ================= Captain appointments =================
        allowed("Core can appoint a captain while Faculty allows it", () -> accounts[1] =
                repo.createUser(core, "New Captain", "newcaptain@flavium.test", TEST_PASSWORD,
                        Role.CAPTAIN, freeBranch.id));
        rejected("A branch cannot have two active captains", ValidationException.class,
                () -> repo.createUser(core, "Second Captain", "captain2@flavium.test",
                        TEST_PASSWORD, Role.CAPTAIN, freeBranch.id));
        rejected("A captain must be given a branch", ValidationException.class,
                () -> repo.createUser(core, "No Branch", "nobranch@flavium.test",
                        TEST_PASSWORD, Role.CAPTAIN, null));
        allowed("Faculty can switch off captain appointments by Cores",
                () -> repo.setFlag(faculty, AppSettings.CORE_CAN_APPOINT_CAPTAINS, false));
        denied("Core cannot change a captain once Faculty has switched that off",
                () -> repo.setUserActive(core, accounts[1], false));
        allowed("Faculty can still deactivate that captain",
                () -> repo.setUserActive(faculty, accounts[1], false));

        // ================= Finalising results =================
        allowed("Faculty can switch off finalising by Cores",
                () -> repo.setFlag(faculty, AppSettings.CORE_CAN_FINALISE, false));
        denied("Core cannot finalise while Faculty has switched that off",
                () -> repo.finaliseMatch(core, assigned.id, repo.getMatch(assigned.id).version));
        allowed("Faculty can switch finalising by Cores back on",
                () -> repo.setFlag(faculty, AppSettings.CORE_CAN_FINALISE, true));
        allowed("Core can finalise a completed match when allowed", () -> repo.finaliseMatch(
                core, assigned.id, repo.getMatch(assigned.id).version));
        rejected("An ordinary update cannot overwrite a final result",
                ValidationException.class, () -> repo.updateScore(core, assigned.id,
                        repo.getMatch(assigned.id).version, 0, 0, "", Match.STATUS_COMPLETED));
        rejected("A correction without a reason is rejected", ValidationException.class,
                () -> repo.correctFinalResult(core, assigned.id,
                        repo.getMatch(assigned.id).version, 3, 2, "", ""));
        allowed("A final result can be corrected with a reason",
                () -> repo.correctFinalResult(core, assigned.id,
                        repo.getMatch(assigned.id).version, 3, 2, "", "Scorer miscounted"));
        check("Each change to a match is recorded in the score history", () -> {
            int entries = repo.getScoreAudit(core, assigned.id).size();
            if (entries != 4) {
                throw new ValidationException("Expected 4 history entries, found " + entries);
            }
        });
        rejected("A stale update is rejected as a conflict", ConflictException.class, () -> {
            Match fresh = repo.getMatch(matchId[0]);
            repo.updateScore(core, fresh.id, fresh.version, 1, 0, "", Match.STATUS_LIVE);
            // Second save still uses the old version number, as a second device would.
            repo.updateScore(asoo, fresh.id, fresh.version, 0, 1, "", Match.STATUS_LIVE);
        });
        allowed("Faculty can complete and finalise any match", () -> {
            Match fresh = repo.getMatch(matchId[0]);
            repo.updateScore(faculty, fresh.id, fresh.version, 2, 1, "", Match.STATUS_COMPLETED);
            repo.finaliseMatch(faculty, fresh.id, repo.getMatch(fresh.id).version);
        });

        // ================= Faculty: accounts =================
        allowed("Faculty can add a Core account", () -> accounts[2] = repo.createUser(faculty,
                "Second Core", "core2@flavium.test", TEST_PASSWORD, Role.CORE, null));
        allowed("Faculty can add another Faculty account", () -> accounts[3] = repo.createUser(
                faculty, "Second Faculty", "faculty2@flavium.test", TEST_PASSWORD,
                Role.FACULTY, null));
        final User secondCore = repo.login("core2@flavium.test", TEST_PASSWORD, Role.CORE);
        allowed("Faculty can deactivate a Core account",
                () -> repo.setUserActive(faculty, accounts[2], false));
        denied("A deactivated account cannot log in",
                () -> repo.login("core2@flavium.test", TEST_PASSWORD, Role.CORE));
        denied("A deactivated account that is still open on a device has no permissions",
                () -> repo.saveSport(secondCore, sport("Late Entry")));
        rejected("Faculty cannot deactivate their own account", ValidationException.class,
                () -> repo.setUserActive(faculty, faculty.id, false));
        rejected("Faculty cannot change their own role", ValidationException.class,
                () -> repo.updateUser(faculty, faculty.id, faculty.name, faculty.email,
                        Role.CORE, null));
        rejected("An account with match history cannot be removed, only deactivated",
                ValidationException.class, () -> repo.deleteUser(faculty, asoo.id));
        rejected("An Asoo Core with open matches cannot be deactivated",
                ValidationException.class, () -> repo.setUserActive(faculty, asoo.id, false));
        allowed("Faculty can remove an unused account",
                () -> repo.deleteUser(faculty, accounts[0]));
        allowed("Faculty can remove the second Faculty account",
                () -> repo.deleteUser(faculty, accounts[3]));
        // ================= Branches =================
        final String[] branchId = new String[1];
        denied("Core cannot add a branch",
                () -> repo.saveBranch(core, branch(null, "Architecture", "ARCH")));
        denied("Captain cannot add a branch",
                () -> repo.saveBranch(captain, branch(null, "Architecture", "ARCH")));
        allowed("Faculty can add a branch",
                () -> branchId[0] = repo.saveBranch(faculty, branch(null, "Architecture", "ARCH")));
        rejected("Two branches cannot have the same name", ValidationException.class,
                () -> repo.saveBranch(faculty, branch(null, "architecture", "AR2")));
        allowed("Faculty can deactivate a branch",
                () -> repo.setBranchActive(faculty, branchId[0], false));
        rejected("A deactivated branch cannot receive new registrations",
                ValidationException.class, () -> {
                    Registration r = participant(ids[1], "Late Player");
                    r.branchId = branchId[0];
                    repo.saveRegistration(core, r);
                });
        rejected("A branch with an active captain cannot be deactivated",
                ValidationException.class,
                () -> repo.setBranchActive(faculty, captain.branchId, false));
        allowed("Faculty can rename a branch", () -> repo.saveBranch(faculty,
                branch(captain.branchId, "Computer Science and Engineering", "CSE")));
        check("Renaming a branch renames its teams", () -> {
            String name = repo.getRegistration(faculty, team[0]).name;
            if (!name.startsWith("Computer Science and Engineering \u2014 Year 3")) {
                throw new ValidationException("Team name was " + name);
            }
        });

        // ================= Passwords =================
        rejected("A password without a number is refused", ValidationException.class,
                () -> repo.createUser(faculty, "Weak", "weak@flavium.test", "onlyletters",
                        Role.ASOO_CORE, null));
        allowed("Faculty can add an account for the password checks", () -> repo.createUser(
                faculty, "Lock Test", "lock@flavium.test", TEST_PASSWORD, Role.ASOO_CORE, null));
        final User lockUser = repo.login("lock@flavium.test", TEST_PASSWORD, Role.ASOO_CORE);
        rejected("Changing your password needs the current password",
                ValidationException.class,
                () -> repo.changeOwnPassword(lockUser, "wrong-guess-1", "Fresh@6789"));
        allowed("An organiser can change their own password",
                () -> repo.changeOwnPassword(lockUser, TEST_PASSWORD, "Fresh@6789"));
        rejected("The old password stops working", ValidationException.class,
                () -> repo.login("lock@flavium.test", TEST_PASSWORD, Role.ASOO_CORE));
        check("Five wrong passwords lock the account, even for the right password", () -> {
            // One wrong attempt was made just above; four more reach the limit.
            for (int i = 0; i < 4; i++) {
                try {
                    repo.login("lock@flavium.test", "wrong-guess-" + i, Role.ASOO_CORE);
                } catch (ValidationException expected) {
                    // each wrong password is refused
                }
            }
            try {
                repo.login("lock@flavium.test", "Fresh@6789", Role.ASOO_CORE);
            } catch (PermissionDeniedException locked) {
                return;
            }
            throw new ValidationException("The account was not locked.");
        });
        allowed("A password reset by Faculty unlocks the account", () -> {
            repo.resetPassword(faculty, lockUser.id, "Reset@4321");
            repo.login("lock@flavium.test", "Reset@4321", Role.ASOO_CORE);
        });

        check("Account changes are written to the administrative log", () -> {
            if (repo.getAdminAudit(faculty).size() < 10) {
                throw new ValidationException("Too few log entries were recorded.");
            }
        });
    }

    // ----- Expectations -----

    /** The action must be refused with PermissionDeniedException. */
    private void denied(String name, Runnable action) {
        rejected(name, PermissionDeniedException.class, action);
    }

    /** The action must be refused with the given kind of error. */
    private void rejected(String name, Class<? extends ArenaException> expected, Runnable action) {
        try {
            action.run();
            results.add(new Result(name, false, "The operation was allowed."));
        } catch (ArenaException e) {
            boolean ok = expected.isInstance(e);
            results.add(new Result(name, ok, e.getMessage()));
        }
    }

    /** The action must succeed. */
    private void allowed(String name, Runnable action) {
        check(name, action);
    }

    private void check(String name, Runnable action) {
        try {
            action.run();
            results.add(new Result(name, true, "OK"));
        } catch (ArenaException e) {
            results.add(new Result(name, false, e.getMessage()));
        }
    }

    // ----- Sample objects -----

    private Sport findSport(String name) {
        for (Sport s : repo.getSports()) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        throw new IllegalStateException("Sample sport not found: " + name);
    }

    private Registration findPending(User core, String categoryId) {
        for (Registration r : repo.getRegistrations(core, categoryId)) {
            if (Registration.STATUS_PENDING.equals(r.status)) {
                return r;
            }
        }
        throw new IllegalStateException("No pending sample registration found");
    }

    private Branch findBranchWithoutCaptain(User existingCaptain) {
        for (Branch b : repo.getBranches()) {
            if (b.active && !b.id.equals(existingCaptain.branchId)) {
                return b;
            }
        }
        throw new IllegalStateException("No free sample branch found");
    }

    private static Sport sport(String name) {
        Sport s = new Sport();
        s.name = name;
        s.description = "";
        return s;
    }

    private static Category category(String sportId, String name) {
        Category c = new Category();
        c.sportId = sportId;
        c.name = name;
        c.type = Category.TYPE_INDIVIDUAL;
        c.minSize = 1;
        c.maxSize = 1;
        c.deadline = "";
        c.format = Category.FORMAT_LEAGUE;
        c.ptsWin = 3;
        c.ptsDraw = 1;
        c.ptsLoss = 0;
        return c;
    }

    private Registration participant(String categoryId, String name) {
        Registration r = new Registration();
        r.categoryId = categoryId;
        r.branchId = sampleBranchId;
        r.year = 1;
        r.name = name;
        r.contactName = name;
        r.contactPhone = "";
        r.members = "";
        return r;
    }

    /** A team of seven for the given branch and year. */
    private static Registration teamEntry(String categoryId, String branchId, int year,
                                          String label) {
        Registration r = new Registration();
        r.categoryId = categoryId;
        r.branchId = branchId;
        r.year = year;
        r.teamLabel = label;
        r.contactName = "Captain";
        r.contactPhone = "9000000099";
        r.members = "A One\nB Two\nC Three\nD Four\nE Five\nF Six\nG Seven";
        return r;
    }

    private static Branch branch(String id, String name, String code) {
        Branch b = new Branch();
        b.id = id;
        b.name = name;
        b.code = code;
        return b;
    }

    private static Match fixture(String categoryId, String teamA, String teamB, String assignedTo) {
        Match m = new Match();
        m.categoryId = categoryId;
        m.teamAId = teamA;
        m.teamBId = teamB;
        m.date = DateUtil.today();
        m.time = "10:00";
        m.venue = "Test Court";
        m.round = "Round 1";
        m.assignedTo = assignedTo;
        return m;
    }

    private static Match copyAsNew(Match source) {
        return fixture(source.categoryId, source.teamAId, source.teamBId, null);
    }
}
