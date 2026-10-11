package com.example.flaviumarena;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.data.PermissionDeniedException;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.User;

import org.junit.Test;

/** Checks the permission rules for each role. Runs on the computer, no phone needed. */
public class AccessControlTest {

    private static User user(String id, String role) {
        User u = new User();
        u.id = id;
        u.role = role;
        return u;
    }

    private static Match match(String assignedTo, boolean isFinal) {
        Match m = new Match();
        m.assignedTo = assignedTo;
        m.isFinal = isFinal;
        return m;
    }

    private final User faculty = user("f1", Role.FACULTY);
    private final User core = user("c1", Role.CORE);
    private final User asoo = user("a1", Role.ASOO_CORE);
    private final User captain = user("b1", Role.CAPTAIN);

    @Test
    public void publicHasNoRoleAndNoWriteAccess() {
        assertNull(AccessControl.roleOf(null));
        assertFalse(AccessControl.isStaff(null));
        assertFalse(AccessControl.canManageSports(null));
        assertFalse(AccessControl.canViewAccounts(null));
        assertFalse(AccessControl.canUpdateScore(null, match(null, false)));
        assertFalse(AccessControl.canFinaliseResults(null, true));
        assertFalse(AccessControl.canManageAccountsOfRole(null, Role.ASOO_CORE, true));
    }

    @Test
    public void unknownOrOldRoleCountsAsPublic() {
        User oldViewer = user("v1", "VIEWER");
        assertNull(AccessControl.roleOf(oldViewer));
        assertFalse(AccessControl.isStaff(oldViewer));
        assertFalse(AccessControl.canManageSports(oldViewer));
    }

    @Test
    public void deactivatedAccountLosesEverything() {
        User formerFaculty = user("f2", Role.FACULTY);
        formerFaculty.active = false;
        assertNull(AccessControl.roleOf(formerFaculty));
        assertFalse(AccessControl.isFaculty(formerFaculty));
        assertFalse(AccessControl.canManageSports(formerFaculty));
        assertFalse(AccessControl.canChangeSettings(formerFaculty));
        assertFalse(AccessControl.canManageAccountsOfRole(formerFaculty, Role.ASOO_CORE, true));
        assertFalse(AccessControl.canUpdateScore(formerFaculty, match(null, false)));
    }

    @Test
    public void facultyCanDoEverything() {
        assertTrue(AccessControl.canManageSports(faculty));
        assertTrue(AccessControl.canManageCategories(faculty));
        assertTrue(AccessControl.canManageRegistrations(faculty));
        assertTrue(AccessControl.canManageFixtures(faculty));
        assertTrue(AccessControl.canChangeSettings(faculty));
        assertTrue(AccessControl.canViewAdminAudit(faculty));
        assertTrue(AccessControl.canFinaliseResults(faculty, false));
        assertTrue(AccessControl.canUpdateScore(faculty, match(null, true)));
        for (String role : Role.ALL) {
            assertTrue(AccessControl.canManageAccountsOfRole(faculty, role, false));
        }
    }

    @Test
    public void coreAdministersTheFestivalButNotFacultyMatters() {
        assertTrue(AccessControl.canManageSports(core));
        assertTrue(AccessControl.canManageCategories(core));
        assertTrue(AccessControl.canManageRegistrations(core));
        assertTrue(AccessControl.canManageFixtures(core));
        assertTrue(AccessControl.canViewAccounts(core));
        assertTrue(AccessControl.canUpdateScore(core, match(null, false)));
        assertFalse(AccessControl.canChangeSettings(core));
        assertFalse(AccessControl.canViewAdminAudit(core));
        assertFalse(AccessControl.canManageBranches(core));
    }

    @Test
    public void coreCanNeverManageFacultyOrCoreAccounts() {
        assertFalse(AccessControl.canManageAccountsOfRole(core, Role.FACULTY, true));
        assertFalse(AccessControl.canManageAccountsOfRole(core, Role.CORE, true));
        assertTrue(AccessControl.canManageAccountsOfRole(core, Role.ASOO_CORE, false));
    }

    @Test
    public void facultySwitchesLimitTheCore() {
        assertTrue(AccessControl.canManageAccountsOfRole(core, Role.CAPTAIN, true));
        assertFalse(AccessControl.canManageAccountsOfRole(core, Role.CAPTAIN, false));
        assertTrue(AccessControl.canFinaliseResults(core, true));
        assertFalse(AccessControl.canFinaliseResults(core, false));
    }

    @Test
    public void asooCoreHasNoAdministration() {
        assertFalse(AccessControl.canManageSports(asoo));
        assertFalse(AccessControl.canManageCategories(asoo));
        assertFalse(AccessControl.canManageRegistrations(asoo));
        assertFalse(AccessControl.canManageFixtures(asoo));
        assertFalse(AccessControl.canViewAccounts(asoo));
        assertFalse(AccessControl.canFinaliseResults(asoo, true));
        for (String role : Role.ALL) {
            assertFalse(AccessControl.canManageAccountsOfRole(asoo, role, true));
        }
    }

    @Test
    public void asooCoreScoresOnlyAssignedMatchesThatAreNotFinal() {
        assertTrue(AccessControl.canUpdateScore(asoo, match("a1", false)));
        assertFalse(AccessControl.canUpdateScore(asoo, match("someone-else", false)));
        assertFalse(AccessControl.canUpdateScore(asoo, match(null, false)));
        assertFalse(AccessControl.canUpdateScore(asoo, match("a1", true)));
    }

    @Test
    public void captainHasNoOrganiserPowers() {
        assertTrue(AccessControl.isCaptain(captain));
        assertFalse(AccessControl.isStaff(captain));
        assertFalse(AccessControl.canManageSports(captain));
        assertFalse(AccessControl.canManageFixtures(captain));
        assertFalse(AccessControl.canManageRegistrations(captain));
        assertFalse(AccessControl.canViewAccounts(captain));
        assertFalse(AccessControl.canUpdateScore(captain, match("b1", false)));
        assertFalse(AccessControl.canFinaliseResults(captain, true));
        for (String role : Role.ALL) {
            assertFalse(AccessControl.canManageAccountsOfRole(captain, role, true));
        }
    }

    @Test
    public void requireThrowsWhenNotAllowed() {
        AccessControl.require(true, core, "add sports");
        try {
            AccessControl.require(false, captain, "add sports");
            fail("Expected PermissionDeniedException");
        } catch (PermissionDeniedException expected) {
            assertTrue(expected.getMessage().contains("Branch Captain"));
        }
        try {
            AccessControl.require(false, null, "add sports");
            fail("Expected PermissionDeniedException");
        } catch (PermissionDeniedException expected) {
            assertTrue(expected.getMessage().contains("public visitor"));
        }
    }
}
