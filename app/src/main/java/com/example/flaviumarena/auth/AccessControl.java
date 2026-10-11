package com.example.flaviumarena.auth;

import com.example.flaviumarena.data.PermissionDeniedException;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.User;

/**
 * All permission rules live in this one class.
 *
 * The screens use it to decide which buttons to show, and the repository calls it
 * again before every write. Hiding a button is therefore never the only protection.
 *
 * A null user is a member of the public: read-only, no account.
 * A deactivated account is treated exactly like the public.
 *
 *   Faculty        - super admin: everything.
 *   Core           - festival administration, but never Faculty or Core accounts
 *                    and never the Faculty-only settings.
 *   Asoo Core      - scores of the matches assigned to them.
 *   Branch Captain - registrations of their own branch, until they are approved.
 */
public final class AccessControl {

    private AccessControl() {
    }

    /** The role that counts for permissions: null for the public and for inactive accounts. */
    public static String roleOf(User user) {
        if (user == null || !user.active || !Role.isValid(user.role)) {
            return null;
        }
        return user.role;
    }

    public static boolean isFaculty(User user) {
        return Role.FACULTY.equals(roleOf(user));
    }

    public static boolean isCore(User user) {
        return Role.CORE.equals(roleOf(user));
    }

    public static boolean isAsooCore(User user) {
        return Role.ASOO_CORE.equals(roleOf(user));
    }

    public static boolean isCoach(User user) {
        return Role.COACH.equals(roleOf(user));
    }

    public static boolean isCaptain(User user) {
        return Role.CAPTAIN.equals(roleOf(user));
    }

    /** Faculty or Core: the people who administer the festival. */
    public static boolean isAdmin(User user) {
        return isFaculty(user) || isCore(user);
    }

    /** Faculty, Core or Asoo Core: the people who run matches. */
    public static boolean isStaff(User user) {
        return isAdmin(user) || isAsooCore(user);
    }

    // ----- Coaches: administrators of the sports assigned to them -----

    /** True when this user is a Coach and Faculty assigned them this sport. */
    public static boolean coaches(User user, String sportId) {
        return isCoach(user) && sportId != null && user.coachSportIds.contains(sportId);
    }

    /**
     * The user as they count inside one sport. Everyone is returned unchanged except a
     * Coach of that sport, who gets a copy marked as its administrator. The checks below
     * that concern categories, registrations, fixtures and results accept that mark;
     * the checks for accounts, branches, settings and sports themselves never do, so a
     * Coach has no authority outside their sports.
     */
    public static User inSport(User user, String sportId) {
        if (user == null) {
            return null;
        }
        User copy = new User();
        copy.id = user.id;
        copy.name = user.name;
        copy.email = user.email;
        copy.role = user.role;
        copy.active = user.active;
        copy.branchId = user.branchId;
        copy.branchName = user.branchName;
        copy.sessionId = user.sessionId;
        copy.coachSportIds = user.coachSportIds;
        copy.coachSportNames = user.coachSportNames;
        copy.sportAdmin = coaches(user, sportId);
        return copy;
    }

    private static boolean sportAdminHere(User user) {
        return isCoach(user) && user.sportAdmin;
    }

    /** Faculty and Core everywhere; a Coach inside a sport assigned to them. */
    private static boolean managesHere(User user) {
        return isAdmin(user) || sportAdminHere(user);
    }

    // ----- Festival administration: Faculty and Core -----

    public static boolean canManageSports(User user) {
        return isAdmin(user);
    }

    public static boolean canManageCategories(User user) {
        return managesHere(user);
    }

    public static boolean canManageRegistrations(User user) {
        return managesHere(user);
    }

    public static boolean canManageFixtures(User user) {
        return managesHere(user);
    }

    public static boolean canViewDashboard(User user) {
        return isAdmin(user);
    }

    public static boolean canViewAccounts(User user) {
        return isAdmin(user);
    }

    // ----- Faculty only -----

    public static boolean canChangeSettings(User user) {
        return isFaculty(user);
    }

    public static boolean canViewAdminAudit(User user) {
        return isFaculty(user);
    }

    public static boolean canManageBranches(User user) {
        return isFaculty(user);
    }

    // ----- Accounts -----

    /**
     * May "actor" create, edit, deactivate or remove an account that has "targetRole"?
     *
     *   Faculty: accounts of every role.
     *   Core:    Asoo Core accounts, and Branch Captain accounts when Faculty allows it.
     *            Never Faculty or Core accounts, so a Core cannot promote anyone
     *            (including themselves) to Core or Faculty.
     *   Others:  no accounts at all.
     */
    public static boolean canManageAccountsOfRole(User actor, String targetRole,
                                                  boolean coreMayAppointCaptains) {
        if (!Role.isValid(targetRole)) {
            return false;
        }
        if (isFaculty(actor)) {
            return true;
        }
        if (isCore(actor)) {
            if (Role.ASOO_CORE.equals(targetRole)) {
                return true;
            }
            return Role.CAPTAIN.equals(targetRole) && coreMayAppointCaptains;
        }
        return false;
    }

    // ----- Branch captains and registrations -----

    private static boolean isCaptainOf(User user, String branchId) {
        return isCaptain(user) && branchId != null && branchId.equals(user.branchId);
    }

    /** Faculty and Core register for any branch. A captain only for their own branch. */
    public static boolean canRegisterForBranch(User user, String branchId) {
        return managesHere(user) || isCaptainOf(user, branchId);
    }

    /**
     * Faculty and Core may edit or delete any registration. A captain may edit or
     * withdraw a registration of their own branch until it has been approved; after
     * approval only Faculty or a Core can change it.
     */
    public static boolean canEditRegistration(User user, Registration registration) {
        if (registration == null) {
            return false;
        }
        if (managesHere(user)) {
            return true;
        }
        return isCaptainOf(user, registration.branchId) && !registration.isApproved();
    }

    /** The logo is the one thing a captain may still change after approval. */
    public static boolean canSetLogo(User user, Registration registration) {
        return registration != null
                && (managesHere(user) || isCaptainOf(user, registration.branchId));
    }

    // ----- Private information -----

    /** Phone numbers and pending or rejected registrations are hidden from the public. */
    public static boolean canViewPrivateRegistrationData(User user) {
        return isStaff(user) || sportAdminHere(user);
    }

    /** A captain also sees the private details of their own branch, and only those. */
    public static boolean canViewPrivateRegistrationData(User user, String branchId) {
        return isStaff(user) || sportAdminHere(user) || isCaptainOf(user, branchId);
    }

    public static boolean canViewScoreHistory(User user) {
        return isStaff(user) || sportAdminHere(user);
    }

    // ----- Match operations -----

    /**
     * Faculty and Core may change the score of any match. An Asoo Core may change only
     * a match that is assigned to them, and only while its result is not final.
     * (Changing a final result additionally needs canFinaliseResults.)
     */
    public static boolean canUpdateScore(User user, Match match) {
        return match != null && canScore(user, match.assignedTo, match.isFinal);
    }

    /**
     * The same rule for anything that is scored, a match or a heat or final:
     * Faculty and Core always; an Asoo Core only when it is assigned to them and the
     * result is not final.
     */
    public static boolean canScore(User user, String assignedTo, boolean isFinal) {
        if (managesHere(user)) {
            return true;
        }
        if (isAsooCore(user)) {
            return !isFinal && user.id != null && user.id.equals(assignedTo);
        }
        return false;
    }

    /**
     * Finalising a result, and correcting a final result: Faculty always,
     * a Core only when Faculty has switched that on.
     */
    public static boolean canFinaliseResults(User user, boolean coreMayFinalise) {
        return isFaculty(user) || (isCore(user) && coreMayFinalise) || sportAdminHere(user);
    }

    /** Throws PermissionDeniedException when the check failed. */
    public static void require(boolean allowed, User user, String action) {
        if (!allowed) {
            String who = roleOf(user) == null ? "A public visitor" : "A " + Role.label(user.role);
            throw new PermissionDeniedException("Permission denied: " + who
                    + " is not allowed to " + action + ".");
        }
    }
}
