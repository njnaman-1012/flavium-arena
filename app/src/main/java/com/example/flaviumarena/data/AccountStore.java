package com.example.flaviumarena.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.AppSettings;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.util.PasswordUtil;
import java.util.ArrayList;
import java.util.List;

import static com.example.flaviumarena.data.Sql.*;

/**
 * Organiser accounts: logging in, passwords and lockout, creating and changing accounts,
 * the sports of a Coach and the branch of a Branch CR.
 * SqliteArenaRepository hands these calls on to this class.
 */
final class AccountStore extends Store {

    AccountStore(SqliteArenaRepository repo, DbHelper helper) {
        super(repo, helper);
    }

    /** Every user query joins the branch, so a captain's branch name is always available. */
    private static final String USER_SELECT =
            "SELECT u.*, b.name AS branch_name FROM users u "
                    + "LEFT JOIN branches b ON b.id = u.branch_id ";

    /** After this many wrong passwords in a row, the account is locked for a while. */
    static final int MAX_FAILED_LOGINS = 5;
    static final long LOCK_MILLIS = 5 * 60 * 1000L;

    /**
     * Checks, in this order: the account is not locked, the password is right, the
     * account is active, and its stored role is the one chosen on the login screen.
     * Choosing a role never grants it: the role always comes from the users table.
     */
    private User checkCredentials(String email, String password, String expectedRole) {
        if (!Role.isValid(expectedRole)) {
            throw new ValidationException("Choose how you are logging in.");
        }
        User user = null;
        String salt = null;
        String stored = null;
        long lockedUntil = 0;
        int failed = 0;
        Cursor c = query(USER_SELECT + "WHERE u.email = ?", clean(email));
        try {
            if (c.moveToFirst()) {
                user = withSports(readUser(c));
                salt = text(c, "salt");
                stored = text(c, "password_hash");
                lockedUntil = c.getLong(c.getColumnIndexOrThrow("locked_until"));
                failed = number(c, "failed_logins");
            }
        } finally {
            c.close();
        }
        if (user == null) {
            throw new ValidationException("Wrong email or password.");
        }
        long now = System.currentTimeMillis();
        if (lockedUntil > now) {
            long minutes = (lockedUntil - now) / 60000 + 1;
            throw new PermissionDeniedException("Too many wrong passwords. This account is "
                    + "locked for about " + minutes + " more minute(s).");
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (!PasswordUtil.verify(password == null ? "" : password, salt, stored)) {
            // Count the failure, and lock the account when there have been too many.
            failed++;
            if (failed >= MAX_FAILED_LOGINS) {
                cv.put("failed_logins", 0);
                cv.put("locked_until", now + LOCK_MILLIS);
            } else {
                cv.put("failed_logins", failed);
            }
            db.update("users", cv, "id = ?", new String[]{user.id});
            throw new ValidationException("Wrong email or password.");
        }

        cv.put("failed_logins", 0);
        cv.put("locked_until", 0);
        if (PasswordUtil.isLegacy(stored)) {
            // An account from the first version of the app: store the password again
            // with the slow hash now that we know what it is.
            String newSalt = PasswordUtil.newSalt();
            cv.put("salt", newSalt);
            cv.put("password_hash", PasswordUtil.hash(password, newSalt));
        }
        db.update("users", cv, "id = ?", new String[]{user.id});

        if (!user.active) {
            throw new PermissionDeniedException("This account has been deactivated. "
                    + "Contact the Faculty in charge.");
        }
        if (!expectedRole.equals(user.role)) {
            throw new PermissionDeniedException("This account is not registered as "
                    + Role.label(expectedRole) + ". Go back and choose the role you were given.");
        }
        return user;
    }

    // ----- Sessions: one live session per account -----

    public User login(String email, String password, String expectedRole) {
        return login(email, password, expectedRole, "this device", false);
    }

    public User login(String email, String password, String expectedRole, String device,
                      boolean takeOver) {
        // The password is checked first, so someone who does not know it learns nothing
        // about whether the account is in use.
        User user = checkCredentials(email, password, expectedRole);
        user.sessionId = repo.sessions.openSession(user, device, takeOver);
        return user;
    }

    public User resumeSession(String sessionId) {
        String userId = repo.sessions.userOfLiveSession(sessionId);
        User user = userId == null ? null : getUser(userId);
        if (user == null) {
            return null;
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (!user.active) {
            SessionStore.endSessions(db, "id = ?", SqliteArenaRepository.END_DEACTIVATED, sessionId);
            return null;
        }
        // Remember that the session is in use, so it does not expire.
        ContentValues cv = new ContentValues();
        cv.put("last_seen_at", System.currentTimeMillis());
        db.update("sessions", cv, "id = ?", new String[]{sessionId});
        user.sessionId = sessionId;
        return user;
    }

    public String getSessionEndReason(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        Cursor c = query("SELECT revoked_reason FROM sessions "
                + "WHERE id = ? AND revoked_at IS NOT NULL", sessionId);
        try {
            return c.moveToFirst() ? c.getString(0) : "";
        } finally {
            c.close();
        }
    }

    public void logout(User actor) {
        if (actor != null && actor.sessionId != null) {
            SessionStore.endSessions(helper.getWritableDatabase(), "id = ?", SqliteArenaRepository.END_LOGGED_OUT,
                    actor.sessionId);
        }
    }

    /** Lets a logged-in organiser change their own password after proving the old one. */
    public void changeOwnPassword(User actorArg, String currentPassword, String newPassword) {
        User actor = repo.trusted(actorArg);
        AccessControl.require(AccessControl.roleOf(actor) != null, actor,
                "change a password");
        String salt;
        String stored;
        Cursor c = query("SELECT salt, password_hash FROM users WHERE id = ?", actor.id);
        try {
            c.moveToFirst();
            salt = c.getString(0);
            stored = c.getString(1);
        } finally {
            c.close();
        }
        if (!PasswordUtil.verify(currentPassword == null ? "" : currentPassword, salt, stored)) {
            throw new ValidationException("Your current password is not correct.");
        }
        checkPassword(newPassword);
        if (newPassword.equals(currentPassword)) {
            throw new ValidationException("The new password must be different.");
        }
        storePassword(actor.id, newPassword);
        repo.audit(actor, "PASSWORD_CHANGED", actor.name + " changed their own password");
    }

    /** Hashes and saves a password, and clears any lock left by wrong attempts. */
    private void storePassword(String userId, String password) {
        String salt = PasswordUtil.newSalt();
        ContentValues cv = new ContentValues();
        cv.put("salt", salt);
        cv.put("password_hash", PasswordUtil.hash(password, salt));
        cv.put("failed_logins", 0);
        cv.put("locked_until", 0);
        helper.getWritableDatabase().update("users", cv, "id = ?", new String[]{userId});
    }

    public User getUser(String id) {
        if (id == null) {
            return null;
        }
        Cursor c = query(USER_SELECT + "WHERE u.id = ?", id);
        try {
            return c.moveToFirst() ? withSports(readUser(c)) : null;
        } finally {
            c.close();
        }
    }

    public boolean hasFaculty() {
        return count(helper.getReadableDatabase(), "users", "role = ?", Role.FACULTY) > 0;
    }

    /**
     * Creates the very first Faculty account. It works only while no Faculty account
     * exists, and only for the email address written in the app's configuration.
     * After that, Faculty accounts can be added by Faculty alone (see createUser).
     */
    public User createFirstFaculty(String name, String email, String password,
                                   String authorisedEmail) {
        String allowed = clean(authorisedEmail);
        if (allowed.isEmpty()) {
            throw new ValidationException("No Faculty email has been configured for this app "
                    + "yet. Set faculty_email in res/values/config.xml and rebuild.");
        }
        if (hasFaculty()) {
            throw new PermissionDeniedException("A Faculty account already exists. "
                    + "Further Faculty accounts can only be added by Faculty.");
        }
        if (!allowed.equalsIgnoreCase(clean(email))) {
            throw new PermissionDeniedException("This is not the authorised Faculty email "
                    + "address for this app.");
        }
        String id = insertUser(name, email, password, Role.FACULTY, null);
        User created = getUser(id);
        repo.audit(created, "FIRST_FACULTY_CREATED", "First Faculty account set up: " + created.name);
        created.sessionId = repo.sessions.openSession(created, "this device", false);
        return created;
    }

    /** Faculty see every account. A Core sees only the accounts a Core may manage. */
    public List<User> getUsers(User actorArg) {
        User actor = repo.trusted(actorArg);
        AccessControl.require(AccessControl.canViewAccounts(actor), actor, "view accounts");

        boolean captains = repo.getFlag(AppSettings.CORE_CAN_APPOINT_CAPTAINS);
        List<User> visible = new ArrayList<>();
        for (User u : readUsers(query(USER_SELECT + "ORDER BY u.name"))) {
            if (u.id.equals(actor.id)
                    || AccessControl.canManageAccountsOfRole(actor, u.role, captains)) {
                visible.add(u);
            }
        }
        return visible;
    }

    public List<User> getAsooCores(User actorArg) {
        User actor = repo.trusted(actorArg);
        AccessControl.require(AccessControl.canManageFixtures(actor)
                || AccessControl.isCoach(actor), actor, "view the list of Asoo Cores");
        return readUsers(query(USER_SELECT + "WHERE u.role = ? AND u.active = 1 ORDER BY u.name",
                Role.ASOO_CORE));
    }

    public String createUser(User actorArg, String name, String email, String password,
                             String role, String branchId) {
        User actor = repo.trusted(actorArg);
        requireAccountAuthority(actor, role, "create " + Role.label(role) + " accounts");

        branchId = checkBranchForRole(role, branchId, null);
        String id = insertUser(name, email, password, role, branchId);
        repo.audit(actor, "ACCOUNT_CREATED", Role.label(role) + " account created: " + clean(name));
        return id;
    }

    public void updateUser(User actorArg, String userId, String name, String email,
                           String role, String branchId) {
        User actor = repo.trusted(actorArg);
        User target = requireUser(userId);
        // The actor needs authority over the role the account has now AND the new role,
        // so nobody can be moved into a role the actor could not create.
        requireAccountAuthority(actor, target.role, "edit " + Role.label(target.role) + " accounts");
        requireAccountAuthority(actor, role, "give someone the " + Role.label(role) + " role");

        boolean roleChanges = !target.role.equals(role);
        if (roleChanges && actor.id.equals(userId)) {
            throw new ValidationException("You cannot change your own role.");
        }
        name = clean(name);
        email = clean(email);
        if (name.isEmpty()) {
            throw new ValidationException("Name is required.");
        }
        if (!email.contains("@") || email.contains(" ")) {
            throw new ValidationException("Enter a valid email address.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (count(db, "users", "email = ? AND id <> ?", email, userId) > 0) {
            throw new ValidationException("Another account already uses this email.");
        }
        if (roleChanges) {
            checkNotLastFaculty(db, target);
            checkNoOpenMatches(db, target);
        }
        branchId = checkBranchForRole(role, branchId, userId);

        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("email", email);
        cv.put("role", role);
        if (branchId == null) {
            cv.putNull("branch_id");
        } else {
            cv.put("branch_id", branchId);
        }
        try {
            db.update("users", cv, "id = ?", new String[]{userId});
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            throw new ValidationException(BRANCH_HAS_CR);
        }
        if (!Role.COACH.equals(role)) {
            // Someone who is no longer a Coach keeps no sports.
            db.delete("coach_sports", "user_id = ?", new String[]{userId});
        }
        repo.audit(actor, "ACCOUNT_UPDATED", name + ": " + Role.label(target.role)
                + (roleChanges ? " changed to " + Role.label(role) : " details edited"));
    }

    public void resetPassword(User actorArg, String userId, String newPassword) {
        User actor = repo.trusted(actorArg);
        User target = requireUser(userId);
        requireAccountAuthority(actor, target.role,
                "reset the password of a " + Role.label(target.role));
        checkPassword(newPassword);

        storePassword(userId, newPassword);
        // Whoever was logged in with the old password is signed out.
        SessionStore.endSessions(helper.getWritableDatabase(), "user_id = ?", SqliteArenaRepository.END_PASSWORD_RESET, userId);
        repo.audit(actor, "PASSWORD_RESET", "Password reset for " + target.name);
    }

    public void setUserActive(User actorArg, String userId, boolean active) {
        User actor = repo.trusted(actorArg);
        User target = requireUser(userId);
        requireAccountAuthority(actor, target.role,
                (active ? "reactivate" : "deactivate") + " a " + Role.label(target.role));
        if (actor.id.equals(userId)) {
            throw new ValidationException("You cannot deactivate your own account.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (active) {
            // Reactivating a captain must not give a branch two captains.
            checkBranchForRole(target.role, target.branchId, userId);
        } else {
            checkNotLastFaculty(db, target);
            checkNoOpenMatches(db, target);
        }
        ContentValues cv = new ContentValues();
        cv.put("active", active ? 1 : 0);
        try {
            db.update("users", cv, "id = ?", new String[]{userId});
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            throw new ValidationException(BRANCH_HAS_CR);
        }
        if (!active) {
            // A deactivated account is signed out at once.
            SessionStore.endSessions(db, "user_id = ?", SqliteArenaRepository.END_DEACTIVATED, userId);
        }
        repo.audit(actor, active ? "ACCOUNT_REACTIVATED" : "ACCOUNT_DEACTIVATED",
                Role.label(target.role) + " " + target.name);
    }

    public void deleteUser(User actorArg, String userId) {
        User actor = repo.trusted(actorArg);
        User target = requireUser(userId);
        requireAccountAuthority(actor, target.role, "remove a " + Role.label(target.role));
        if (actor.id.equals(userId)) {
            throw new ValidationException("You cannot remove your own account.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        checkNotLastFaculty(db, target);
        if (count(db, "matches", "assigned_to = ? OR updated_by = ?", userId, userId) > 0
                || count(db, "score_audit", "changed_by = ?", userId) > 0
                || count(db, "event_rounds", "assigned_to = ? OR updated_by = ?", userId,
                userId) > 0
                || count(db, "event_audit", "changed_by = ?", userId) > 0) {
            throw new ValidationException(target.name + " appears in match records, so the "
                    + "account cannot be removed. Deactivate it instead.");
        }
        db.delete("users", "id = ?", new String[]{userId});
        repo.audit(actor, "ACCOUNT_REMOVED", Role.label(target.role) + " " + target.name);
    }

    // ----- Coaches -----

    /** Adds the sports of a Coach to the user object. Other roles have none. */
    private User withSports(User user) {
        if (user == null || !Role.COACH.equals(user.role)) {
            return user;
        }
        StringBuilder names = new StringBuilder();
        Cursor c = query("SELECT s.id, s.name FROM coach_sports cs JOIN sports s "
                + "ON s.id = cs.sport_id WHERE cs.user_id = ? ORDER BY s.name", user.id);
        try {
            while (c.moveToNext()) {
                user.coachSportIds.add(c.getString(0));
                names.append(names.length() > 0 ? ", " : "").append(c.getString(1));
            }
        } finally {
            c.close();
        }
        user.coachSportNames = names.toString();
        return user;
    }

    public void setCoachSports(User actorArg, String userId, List<String> sportIds) {
        User actor = repo.trusted(actorArg);
        User target = requireUser(userId);
        requireAccountAuthority(actor, Role.COACH, "assign sports to a Coach");
        if (!Role.COACH.equals(target.role)) {
            throw new ValidationException(target.name + " is not a Coach.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        List<String> names = new ArrayList<>();
        for (String sportId : sportIds) {
            Sport sport = repo.getSport(sportId);
            if (sport == null) {
                throw new ValidationException("One of the chosen sports no longer exists.");
            }
            names.add(sport.name);
        }
        db.beginTransaction();
        try {
            db.delete("coach_sports", "user_id = ?", new String[]{userId});
            for (String sportId : new java.util.LinkedHashSet<>(sportIds)) {
                ContentValues cv = new ContentValues();
                cv.put("user_id", userId);
                cv.put("sport_id", sportId);
                db.insertOrThrow("coach_sports", null, cv);
            }
            DbHelper.writeAdminAudit(db, actor.id, actor.name, "COACH_SPORTS",
                    "Coach " + target.name + " now looks after: "
                            + (names.isEmpty() ? "no sports" : android.text.TextUtils.join(", ",
                            names)));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ----- Account helpers -----

    private void requireAccountAuthority(User actor, String role, String action) {
        if (!Role.isValid(role)) {
            throw new ValidationException("Choose a valid role.");
        }
        AccessControl.require(AccessControl.canManageAccountsOfRole(actor, role,
                repo.getFlag(AppSettings.CORE_CAN_APPOINT_CAPTAINS)), actor, action);
    }

    private User requireUser(String id) {
        User user = getUser(id);
        if (user == null) {
            throw new ValidationException("This account no longer exists.");
        }
        return user;
    }

    private static void checkPassword(String password) {
        String problem = PasswordUtil.problemWith(password);
        if (problem != null) {
            throw new ValidationException(problem);
        }
    }

    /** There must always be at least one active Faculty account. */
    private void checkNotLastFaculty(SQLiteDatabase db, User target) {
        if (Role.FACULTY.equals(target.role) && target.active
                && count(db, "users", "role = ? AND active = 1", Role.FACULTY) <= 1) {
            throw new ValidationException("This is the only active Faculty account. "
                    + "Add another Faculty account first.");
        }
    }

    /** An Asoo Core who still has matches to score cannot be removed from that duty. */
    private void checkNoOpenMatches(SQLiteDatabase db, User target) {
        if (count(db, "matches", "assigned_to = ? AND is_final = 0", target.id) > 0
                || count(db, "event_rounds", "assigned_to = ? AND is_final = 0",
                target.id) > 0) {
            throw new ValidationException(target.name + " is still assigned to matches that "
                    + "are not final. Reassign those matches first.");
        }
    }

    /**
     * A Branch Captain must have exactly one active branch, and a branch has one active
     * captain. Every other role has no branch. Returns the branch id to store.
     */
    private String checkBranchForRole(String role, String branchId, String userIdToIgnore) {
        if (!Role.CAPTAIN.equals(role)) {
            return null;
        }
        if (branchId == null) {
            throw new ValidationException("Choose the branch this Branch CR manages.");
        }
        SQLiteDatabase db = helper.getReadableDatabase();
        if (count(db, "branches", "id = ? AND active = 1", branchId) == 0) {
            throw new ValidationException("Choose an active branch.");
        }
        String ignore = userIdToIgnore == null ? "" : userIdToIgnore;
        if (count(db, "users", "role = ? AND active = 1 AND branch_id = ? AND id <> ?",
                Role.CAPTAIN, branchId, ignore) > 0) {
            throw new ValidationException("This branch already has an active Branch CR. "
                    + "Deactivate or reassign that Branch CR first.");
        }
        return branchId;
    }

    /** Validates the details and stores a new account with a salted password hash. */
    private String insertUser(String name, String email, String password, String role,
                              String branchId) {
        name = clean(name);
        email = clean(email);
        if (name.isEmpty()) {
            throw new ValidationException("Name is required.");
        }
        if (!email.contains("@") || email.contains(" ")) {
            throw new ValidationException("Enter a valid email address.");
        }
        checkPassword(password);
        SQLiteDatabase db = helper.getWritableDatabase();
        if (count(db, "users", "email = ?", email) > 0) {
            throw new ValidationException("An account with this email already exists.");
        }

        String id = newId();
        String salt = PasswordUtil.newSalt();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name);
        cv.put("email", email);
        cv.put("salt", salt);
        cv.put("password_hash", PasswordUtil.hash(password, salt));
        cv.put("role", role);
        cv.put("active", 1);
        if (branchId != null) {
            cv.put("branch_id", branchId);
        }
        try {
            db.insertOrThrow("users", null, cv);
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            // Two people appointing a Branch CR for one branch at the same moment:
            // the database rule lets only the first one through.
            throw new ValidationException(BRANCH_HAS_CR);
        }
        return id;
    }

    private static final String BRANCH_HAS_CR = "This branch already has an active Branch CR. Deactivate or reassign that Branch CR first.";

    private List<User> readUsers(Cursor c) {
        List<User> list = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                list.add(withSports(readUser(c)));
            }
        } finally {
            c.close();
        }
        return list;
    }
}
