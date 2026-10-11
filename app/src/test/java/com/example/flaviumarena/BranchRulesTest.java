package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.User;

import org.junit.Test;

/** Branch-based naming and the captain's limits. Runs on the computer, no phone needed. */
public class BranchRulesTest {

    private static User user(String role, String branchId) {
        User u = new User();
        u.id = role;
        u.role = role;
        u.branchId = branchId;
        return u;
    }

    private static Registration entry(String branchId, String status) {
        Registration r = new Registration();
        r.branchId = branchId;
        r.status = status;
        return r;
    }

    private final User faculty = user(Role.FACULTY, null);
    private final User core = user(Role.CORE, null);
    private final User asoo = user(Role.ASOO_CORE, null);
    private final User captain = user(Role.CAPTAIN, "ce");

    @Test
    public void teamNameIsBranchYearAndOptionalLabel() {
        assertEquals("Computer Engineering — Year 3",
                Registration.teamName("Computer Engineering", 3, ""));
        assertEquals("Computer Engineering — Year 3 (Team B)",
                Registration.teamName("Computer Engineering", 3, " Team B "));
        assertEquals("Computer Engineering — Year 3",
                Registration.teamName("Computer Engineering", 3, null));
    }

    @Test
    public void unknownYearShowsTheBranchOnly() {
        assertEquals("Unassigned", Registration.branchLine("Unassigned", 0));
        assertEquals("", Registration.branchLine(null, 2));
        assertEquals("Civil Engineering — Year 4",
                Registration.branchLine("Civil Engineering", 4));
    }

    @Test
    public void individualsAreListedWithTheirBranchCode() {
        Match m = new Match();
        m.categoryType = Category.TYPE_INDIVIDUAL;
        m.teamAName = "Ananya Rao";
        m.branchACode = "CE";
        m.teamBName = "Meera Nair";
        m.branchBCode = "";
        assertEquals("Ananya Rao (CE)", m.sideAText());
        assertEquals("Meera Nair", m.sideBText());

        m.categoryType = Category.TYPE_TEAM;
        m.teamAName = "Computer Engineering — Year 3";
        assertEquals("Computer Engineering — Year 3", m.sideAText());
    }

    @Test
    public void captainRegistersOnlyForOwnBranch() {
        assertTrue(AccessControl.canRegisterForBranch(captain, "ce"));
        assertFalse(AccessControl.canRegisterForBranch(captain, "it"));
        assertFalse(AccessControl.canRegisterForBranch(captain, null));
        assertTrue(AccessControl.canRegisterForBranch(core, "it"));
        assertTrue(AccessControl.canRegisterForBranch(faculty, "it"));
        assertFalse(AccessControl.canRegisterForBranch(asoo, "ce"));
        assertFalse(AccessControl.canRegisterForBranch(null, "ce"));
    }

    @Test
    public void captainEditsOwnBranchUntilApproval() {
        assertTrue(AccessControl.canEditRegistration(captain,
                entry("ce", Registration.STATUS_PENDING)));
        assertTrue(AccessControl.canEditRegistration(captain,
                entry("ce", Registration.STATUS_REJECTED)));
        assertFalse(AccessControl.canEditRegistration(captain,
                entry("ce", Registration.STATUS_APPROVED)));
        assertFalse(AccessControl.canEditRegistration(captain,
                entry("it", Registration.STATUS_PENDING)));
        assertTrue(AccessControl.canEditRegistration(core,
                entry("it", Registration.STATUS_APPROVED)));
        assertFalse(AccessControl.canEditRegistration(asoo,
                entry("ce", Registration.STATUS_PENDING)));
    }

    @Test
    public void captainMayAlwaysSetOwnLogoButNeverAnothers() {
        assertTrue(AccessControl.canSetLogo(captain, entry("ce", Registration.STATUS_APPROVED)));
        assertFalse(AccessControl.canSetLogo(captain, entry("it", Registration.STATUS_PENDING)));
        assertFalse(AccessControl.canSetLogo(asoo, entry("ce", Registration.STATUS_PENDING)));
        assertFalse(AccessControl.canSetLogo(null, entry("ce", Registration.STATUS_PENDING)));
    }

    @Test
    public void privateDetailsOfABranchAreForStaffAndItsOwnCaptain() {
        assertTrue(AccessControl.canViewPrivateRegistrationData(captain, "ce"));
        assertFalse(AccessControl.canViewPrivateRegistrationData(captain, "it"));
        assertTrue(AccessControl.canViewPrivateRegistrationData(asoo, "it"));
        assertFalse(AccessControl.canViewPrivateRegistrationData(null, "ce"));
    }

    @Test
    public void deactivatedCaptainLosesTheBranch() {
        User former = user(Role.CAPTAIN, "ce");
        former.active = false;
        assertFalse(AccessControl.canRegisterForBranch(former, "ce"));
        assertFalse(AccessControl.canSetLogo(former, entry("ce", Registration.STATUS_PENDING)));
        assertFalse(AccessControl.canViewPrivateRegistrationData(former, "ce"));
    }
}
