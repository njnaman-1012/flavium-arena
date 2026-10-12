package com.example.flaviumarena.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.AdminAudit;
import com.example.flaviumarena.model.AppSettings;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.DashboardStats;
import com.example.flaviumarena.model.EventEntry;
import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.MedalRow;
import com.example.flaviumarena.model.MedalWin;
import com.example.flaviumarena.model.MatchEvent;
import com.example.flaviumarena.model.MatchPeriod;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.ScoreAudit;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.StandingRow;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.scoring.Medals;
import com.example.flaviumarena.util.DateUtil;
import com.example.flaviumarena.util.PasswordUtil;
import com.example.flaviumarena.scoring.StandingsCalculator;

import static com.example.flaviumarena.data.Sql.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The local (SQLite) implementation of ArenaRepository.
 *
 * Every method that changes data follows the same four steps:
 *   1. re-read the acting user from the database (so the role cannot be faked),
 *   2. check the permission with AccessControl,
 *   3. validate the input and the related records,
 *   4. write to the database.
 */
public class SqliteArenaRepository implements ArenaRepository {

    private final DbHelper helper;
    private final SessionStore sessions;
    private final MatchStore matches;
    private final EventStore events;

    public SqliteArenaRepository(DbHelper helper) {
        this.helper = helper;
        this.sessions = new SessionStore(helper);
        this.matches = new MatchStore(this, helper);
        this.events = new EventStore(this, helper);
    }

    // =====================================================================
    // Users
    // =====================================================================

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

    public static final String END_TAKEN_OVER = "TAKEN_OVER";
    public static final String END_EXPIRED = "EXPIRED";
    public static final String END_LOGGED_OUT = "LOGGED_OUT";
    public static final String END_DEACTIVATED = "ACCOUNT_DEACTIVATED";
    public static final String END_PASSWORD_RESET = "PASSWORD_RESET";

    @Override
    public User login(String email, String password, String expectedRole) {
        return login(email, password, expectedRole, "this device", false);
    }

    @Override
    public User login(String email, String password, String expectedRole, String device,
                      boolean takeOver) {
        // The password is checked first, so someone who does not know it learns nothing
        // about whether the account is in use.
        User user = checkCredentials(email, password, expectedRole);
        user.sessionId = sessions.openSession(user, device, takeOver);
        return user;
    }

    @Override
    public User resumeSession(String sessionId) {
        String userId = sessions.userOfLiveSession(sessionId);
        User user = userId == null ? null : getUser(userId);
        if (user == null) {
            return null;
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (!user.active) {
            SessionStore.endSessions(db, "id = ?", END_DEACTIVATED, sessionId);
            return null;
        }
        // Remember that the session is in use, so it does not expire.
        ContentValues cv = new ContentValues();
        cv.put("last_seen_at", System.currentTimeMillis());
        db.update("sessions", cv, "id = ?", new String[]{sessionId});
        user.sessionId = sessionId;
        return user;
    }

    @Override
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

    @Override
    public void logout(User actor) {
        if (actor != null && actor.sessionId != null) {
            SessionStore.endSessions(helper.getWritableDatabase(), "id = ?", END_LOGGED_OUT,
                    actor.sessionId);
        }
    }

    /** Lets a logged-in organiser change their own password after proving the old one. */
    @Override
    public void changeOwnPassword(User actorArg, String currentPassword, String newPassword) {
        User actor = trusted(actorArg);
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
        audit(actor, "PASSWORD_CHANGED", actor.name + " changed their own password");
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

    @Override
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

    @Override
    public boolean hasFaculty() {
        return count(helper.getReadableDatabase(), "users", "role = ?", Role.FACULTY) > 0;
    }

    /**
     * Creates the very first Faculty account. It works only while no Faculty account
     * exists, and only for the email address written in the app's configuration.
     * After that, Faculty accounts can be added by Faculty alone (see createUser).
     */
    @Override
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
        audit(created, "FIRST_FACULTY_CREATED", "First Faculty account set up: " + created.name);
        created.sessionId = sessions.openSession(created, "this device", false);
        return created;
    }

    /** Faculty see every account. A Core sees only the accounts a Core may manage. */
    @Override
    public List<User> getUsers(User actorArg) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canViewAccounts(actor), actor, "view accounts");

        boolean captains = getFlag(AppSettings.CORE_CAN_APPOINT_CAPTAINS);
        List<User> visible = new ArrayList<>();
        for (User u : readUsers(query(USER_SELECT + "ORDER BY u.name"))) {
            if (u.id.equals(actor.id)
                    || AccessControl.canManageAccountsOfRole(actor, u.role, captains)) {
                visible.add(u);
            }
        }
        return visible;
    }

    @Override
    public List<User> getAsooCores(User actorArg) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageFixtures(actor)
                || AccessControl.isCoach(actor), actor, "view the list of Asoo Cores");
        return readUsers(query(USER_SELECT + "WHERE u.role = ? AND u.active = 1 ORDER BY u.name",
                Role.ASOO_CORE));
    }

    @Override
    public String createUser(User actorArg, String name, String email, String password,
                             String role, String branchId) {
        User actor = trusted(actorArg);
        requireAccountAuthority(actor, role, "create " + Role.label(role) + " accounts");

        branchId = checkBranchForRole(role, branchId, null);
        String id = insertUser(name, email, password, role, branchId);
        audit(actor, "ACCOUNT_CREATED", Role.label(role) + " account created: " + clean(name));
        return id;
    }

    @Override
    public void updateUser(User actorArg, String userId, String name, String email,
                           String role, String branchId) {
        User actor = trusted(actorArg);
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
        audit(actor, "ACCOUNT_UPDATED", name + ": " + Role.label(target.role)
                + (roleChanges ? " changed to " + Role.label(role) : " details edited"));
    }

    @Override
    public void resetPassword(User actorArg, String userId, String newPassword) {
        User actor = trusted(actorArg);
        User target = requireUser(userId);
        requireAccountAuthority(actor, target.role,
                "reset the password of a " + Role.label(target.role));
        checkPassword(newPassword);

        storePassword(userId, newPassword);
        // Whoever was logged in with the old password is signed out.
        SessionStore.endSessions(helper.getWritableDatabase(), "user_id = ?", END_PASSWORD_RESET, userId);
        audit(actor, "PASSWORD_RESET", "Password reset for " + target.name);
    }

    @Override
    public void setUserActive(User actorArg, String userId, boolean active) {
        User actor = trusted(actorArg);
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
            SessionStore.endSessions(db, "user_id = ?", END_DEACTIVATED, userId);
        }
        audit(actor, active ? "ACCOUNT_REACTIVATED" : "ACCOUNT_DEACTIVATED",
                Role.label(target.role) + " " + target.name);
    }

    @Override
    public void deleteUser(User actorArg, String userId) {
        User actor = trusted(actorArg);
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
        audit(actor, "ACCOUNT_REMOVED", Role.label(target.role) + " " + target.name);
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

    @Override
    public void setCoachSports(User actorArg, String userId, List<String> sportIds) {
        User actor = trusted(actorArg);
        User target = requireUser(userId);
        requireAccountAuthority(actor, Role.COACH, "assign sports to a Coach");
        if (!Role.COACH.equals(target.role)) {
            throw new ValidationException(target.name + " is not a Coach.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        List<String> names = new ArrayList<>();
        for (String sportId : sportIds) {
            Sport sport = getSport(sportId);
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

    /** The trusted user as they count inside one sport (see AccessControl.inSport). */
    User actorIn(User actorArg, String sportId) {
        return AccessControl.inSport(trusted(actorArg), sportId);
    }

    private String lookup(String sql, String argument) {
        if (argument == null) {
            return null;
        }
        Cursor c = query(sql, argument);
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    String sportOfCategory(String categoryId) {
        return lookup("SELECT sport_id FROM categories WHERE id = ?", categoryId);
    }

    private String sportOfRegistration(String registrationId) {
        return lookup("SELECT c.sport_id FROM registrations r JOIN categories c "
                + "ON c.id = r.category_id WHERE r.id = ?", registrationId);
    }

    String sportOfMatch(String matchId) {
        return lookup("SELECT c.sport_id FROM matches m JOIN categories c "
                + "ON c.id = m.category_id WHERE m.id = ?", matchId);
    }

    String sportOfMatchEvent(String eventId) {
        return lookup("SELECT c.sport_id FROM match_events e JOIN matches m ON m.id = e.match_id "
                + "JOIN categories c ON c.id = m.category_id WHERE e.id = ?", eventId);
    }

    String sportOfRound(String roundId) {
        return lookup("SELECT c.sport_id FROM event_rounds r JOIN categories c "
                + "ON c.id = r.category_id WHERE r.id = ?", roundId);
    }

    String sportOfEntry(String entryId) {
        return lookup("SELECT c.sport_id FROM event_entries e JOIN event_rounds r "
                + "ON r.id = e.round_id JOIN categories c ON c.id = r.category_id "
                + "WHERE e.id = ?", entryId);
    }

    // ----- Account helpers -----

    private void requireAccountAuthority(User actor, String role, String action) {
        if (!Role.isValid(role)) {
            throw new ValidationException("Choose a valid role.");
        }
        AccessControl.require(AccessControl.canManageAccountsOfRole(actor, role,
                getFlag(AppSettings.CORE_CAN_APPOINT_CAPTAINS)), actor, action);
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

    // =====================================================================
    // Branches, settings and the administrative log
    // =====================================================================

    /** Branch names are public information. */
    @Override
    public List<Branch> getBranches() {
        List<Branch> list = new ArrayList<>();
        Cursor c = query("SELECT * FROM branches ORDER BY name");
        try {
            while (c.moveToNext()) {
                Branch b = new Branch();
                b.id = text(c, "id");
                b.name = text(c, "name");
                b.code = text(c, "code");
                b.active = number(c, "active") == 1;
                list.add(b);
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** A switch from the settings table. Unknown names count as "off". */
    @Override
    public boolean getFlag(String key) {
        Cursor c = query("SELECT value FROM settings WHERE name = ?", key);
        try {
            return c.moveToFirst() && c.getInt(0) == 1;
        } finally {
            c.close();
        }
    }

    @Override
    public void setFlag(User actorArg, String key, boolean value) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canChangeSettings(actor), actor,
                "change administrative settings");
        if (!AppSettings.isValid(key)) {
            throw new ValidationException("Unknown setting.");
        }
        ContentValues cv = new ContentValues();
        cv.put("name", key);
        cv.put("value", value ? 1 : 0);
        helper.getWritableDatabase().insertWithOnConflict("settings", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
        audit(actor, "SETTING_CHANGED", key + " switched " + (value ? "on" : "off"));
    }

    @Override
    public List<AdminAudit> getAdminAudit(User actorArg) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canViewAdminAudit(actor), actor,
                "view the administrative log");
        List<AdminAudit> list = new ArrayList<>();
        Cursor c = query("SELECT * FROM admin_audit ORDER BY changed_at DESC, rowid DESC "
                + "LIMIT 200");
        try {
            while (c.moveToNext()) {
                AdminAudit a = new AdminAudit();
                a.id = text(c, "id");
                a.actorName = text(c, "actor_name");
                a.changedAt = c.getLong(c.getColumnIndexOrThrow("changed_at"));
                a.action = text(c, "action");
                a.detail = text(c, "detail");
                list.add(a);
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** Records an account, role or settings change. Never contains a password. */
    private void audit(User actor, String action, String detail) {
        DbHelper.writeAdminAudit(helper.getWritableDatabase(), actor.id, actor.name, action,
                detail);
    }

    // =====================================================================
    // Sports
    // =====================================================================

    @Override
    public List<Sport> getSports() {
        List<Sport> list = new ArrayList<>();
        Cursor c = query("SELECT * FROM sports ORDER BY name");
        try {
            while (c.moveToNext()) {
                list.add(readSport(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    @Override
    public Sport getSport(String id) {
        if (id == null) {
            return null;
        }
        Cursor c = query("SELECT * FROM sports WHERE id = ?", id);
        try {
            return c.moveToFirst() ? readSport(c) : null;
        } finally {
            c.close();
        }
    }

    // ----- Sport catalogue -----

    @Override
    public List<SportTemplate> getTemplates() {
        return TemplateStore.readAll(helper.getReadableDatabase());
    }

    @Override
    public SportTemplate getTemplate(String key) {
        return TemplateStore.read(helper.getReadableDatabase(), key);
    }

    /** The template of a sport. A sport whose template has gone is treated as custom. */
    private SportTemplate templateOf(Sport sport) {
        SportTemplate template = getTemplate(sport.templateKey);
        return template != null ? template : getTemplate(SportTemplate.CUSTOM);
    }

    @Override
    public String addSportFromTemplate(User actorArg, String templateKey, String name,
                                       String description) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageSports(actor), actor, "add sports");

        SportTemplate template = getTemplate(templateKey);
        if (template == null) {
            throw new ValidationException("This sport is not in the catalogue.");
        }
        // A catalogue sport takes the catalogue name; only a custom sport is named freely.
        String sportName = template.isCustom() ? clean(name) : template.name;
        if (sportName.isEmpty()) {
            throw new ValidationException("Sport name is required.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (count(db, "sports", "name = ?", sportName) > 0) {
            throw new ValidationException(sportName + " has already been added. "
                    + "Open it to add more categories.");
        }
        String id = newId();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", sportName);
        cv.put("description", clean(description).isEmpty() ? template.description
                : clean(description));
        cv.put("template_key", template.key);
        db.insertOrThrow("sports", null, cv);
        return id;
    }

    @Override
    public String saveSport(User actorArg, Sport sport) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageSports(actor), actor, "add or edit sports");

        String name = clean(sport.name);
        if (name.isEmpty()) {
            throw new ValidationException("Sport name is required.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        boolean isNew = sport.id == null;
        String id = isNew ? newId() : sport.id;
        if (count(db, "sports", "name = ? AND id <> ?", name, id) > 0) {
            throw new ValidationException("A sport named \"" + name + "\" already exists.");
        }

        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("description", clean(sport.description));
        if (isNew) {
            cv.put("id", id);
            db.insertOrThrow("sports", null, cv);
        } else if (db.update("sports", cv, "id = ?", new String[]{id}) == 0) {
            throw new ValidationException("This sport no longer exists.");
        }
        return id;
    }

    @Override
    public void deleteSport(User actorArg, String id) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageSports(actor), actor, "delete sports");

        SQLiteDatabase db = helper.getWritableDatabase();
        if (count(db, "categories", "sport_id = ?", id) > 0) {
            throw new ValidationException("This sport still has categories. "
                    + "Delete its categories first.");
        }
        db.delete("sports", "id = ?", new String[]{id});
    }

    // =====================================================================
    // Categories
    // =====================================================================

    @Override
    public List<Category> getCategories(String sportId) {
        List<Category> list = new ArrayList<>();
        Cursor c = query("SELECT * FROM categories WHERE sport_id = ? ORDER BY name", sportId);
        try {
            while (c.moveToNext()) {
                list.add(readCategory(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    @Override
    public Category getCategory(String id) {
        if (id == null) {
            return null;
        }
        Cursor c = query("SELECT * FROM categories WHERE id = ?", id);
        try {
            return c.moveToFirst() ? readCategory(c) : null;
        } finally {
            c.close();
        }
    }

    @Override
    public String saveCategory(User actorArg, Category category) {
        User actor = actorIn(actorArg,
                category.id == null ? category.sportId : sportOfCategory(category.id));
        AccessControl.require(AccessControl.canManageCategories(actor), actor,
                "add or edit categories");

        String name = clean(category.name);
        String deadline = clean(category.deadline);
        Sport sport = getSport(category.sportId);
        if (sport == null) {
            throw new ValidationException("The sport of this category no longer exists.");
        }

        // When rules are given, they are checked against the sport's template and the
        // classic columns (type, team size, format, points, engine) are worked out
        // from them. A category saved without rules keeps the values it was given.
        Category stored = category.id == null ? null : getCategory(category.id);
        String rulesText = clean(category.rules);
        if (!rulesText.isEmpty()) {
            SportTemplate template = templateOf(sport);
            RuleSet rules = RuleSet.parse(rulesText);
            String problem = template.problemWith(rules);
            if (problem != null) {
                throw new ValidationException(problem);
            }
            int bestOf = rules.getInt("sets_best_of", 1);
            if (SportTemplate.ENGINE_SETS.equals(template.engineFor(rules)) && bestOf % 2 == 0) {
                throw new ValidationException("\"Best of\" must be an odd number, so that a "
                        + "match always has a winner.");
            }
            CategoryRules.applyTo(category, template, rules);
            rulesText = rules.toText();
        } else if (category.engine == null || category.engine.isEmpty()) {
            category.engine = stored == null ? SportTemplate.ENGINE_SCORE : stored.engine;
        }

        // Once matches of the category have started, its rules are locked. Faculty can
        // still change them, and that change is written to the administrative log.
        boolean rulesChanged = stored != null && !rulesText.equals(stored.rules);
        if (rulesChanged && hasStartedMatches(category.id)) {
            if (!AccessControl.isFaculty(actor)) {
                throw new ValidationException("The rules of this category are locked because "
                        + "its matches have started. Only Faculty can change them now.");
            }
            audit(actor, "RULES_CHANGED", "Rules of \"" + name + "\" changed after its "
                    + "matches had started");
        }
        if (name.isEmpty()) {
            throw new ValidationException("Category name is required.");
        }
        boolean team = Category.TYPE_TEAM.equals(category.type);
        if (!team && !Category.TYPE_INDIVIDUAL.equals(category.type)) {
            throw new ValidationException("Choose Individual or Team.");
        }
        int minSize = team ? category.minSize : 1;
        int maxSize = team ? category.maxSize : 1;
        if (minSize < 1 || maxSize < minSize) {
            throw new ValidationException("Team size limits must be at least 1, and the "
                    + "maximum cannot be smaller than the minimum.");
        }
        if (!deadline.isEmpty() && !DateUtil.isValidDate(deadline)) {
            throw new ValidationException("The registration deadline must be a valid date.");
        }
        if (!Category.FORMAT_LEAGUE.equals(category.format)
                && !Category.FORMAT_KNOCKOUT.equals(category.format)) {
            throw new ValidationException("Choose League or Knockout.");
        }
        if (category.ptsWin < 0 || category.ptsDraw < 0 || category.ptsLoss < 0) {
            throw new ValidationException("Points cannot be negative.");
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        boolean isNew = category.id == null;
        String id = isNew ? newId() : category.id;
        if (count(db, "categories", "sport_id = ? AND name = ? AND id <> ?",
                category.sportId, name, id) > 0) {
            throw new ValidationException("This sport already has a category named \""
                    + name + "\".");
        }

        ContentValues cv = new ContentValues();
        cv.put("sport_id", category.sportId);
        cv.put("name", name);
        cv.put("type", category.type);
        cv.put("min_size", minSize);
        cv.put("max_size", maxSize);
        cv.put("deadline", deadline);
        cv.put("format", category.format);
        cv.put("pts_win", category.ptsWin);
        cv.put("pts_draw", category.ptsDraw);
        cv.put("pts_loss", category.ptsLoss);
        cv.put("engine", category.engine);
        cv.put("rules", rulesText);
        if (isNew) {
            cv.put("id", id);
            db.insertOrThrow("categories", null, cv);
        } else if (db.update("categories", cv, "id = ?", new String[]{id}) == 0) {
            throw new ValidationException("This category no longer exists.");
        }
        return id;
    }

    /**
     * True when any match of the category is live, finished, postponed or has a score,
     * or any of its heats or finals has started.
     */
    private boolean hasStartedMatches(String categoryId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        return count(db, "matches",
                "category_id = ? AND (status <> 'UPCOMING' OR score_a IS NOT NULL)",
                categoryId) > 0
                || count(db, "event_rounds", "category_id = ? AND status <> 'UPCOMING'",
                categoryId) > 0;
    }

    @Override
    public void deleteCategory(User actorArg, String id) {
        User actor = actorIn(actorArg, sportOfCategory(id));
        AccessControl.require(AccessControl.canManageCategories(actor), actor,
                "delete categories");

        SQLiteDatabase db = helper.getWritableDatabase();
        if (count(db, "matches", "category_id = ?", id) > 0
                || count(db, "event_rounds", "category_id = ?", id) > 0
                || count(db, "registrations", "category_id = ?", id) > 0) {
            throw new ValidationException("This category still has registrations or fixtures. "
                    + "Delete those first.");
        }
        db.delete("categories", "id = ?", new String[]{id});
    }

    // =====================================================================
    // Registrations
    // =====================================================================

    /** A registration with the names of its branch, category and sport. */
    static final String REG_SELECT =
            "SELECT r.*, b.name AS branch_name, b.code AS branch_code, "
                    + "c.name AS category_name, s.name AS sport_name, s.id AS sport_id "
                    + "FROM registrations r "
                    + "LEFT JOIN branches b ON b.id = r.branch_id "
                    + "JOIN categories c ON c.id = r.category_id "
                    + "JOIN sports s ON s.id = c.sport_id ";

    @Override
    public List<Registration> getRegistrations(User actorArg, String categoryId) {
        return visibleRegistrations(trusted(actorArg), "r.category_id = ?", categoryId,
                "r.name");
    }

    @Override
    public List<Registration> getRegistrationsOfBranch(User actorArg, String branchId) {
        return visibleRegistrations(trusted(actorArg), "r.branch_id = ?", branchId,
                "s.name, c.name, r.name");
    }

    private List<Registration> visibleRegistrations(User actor, String where, String argument,
                                                   String order) {
        List<Registration> list = new ArrayList<>();
        Cursor c = query(REG_SELECT + "WHERE " + where + " ORDER BY " + order, argument);
        try {
            while (c.moveToNext()) {
                Registration r = forViewer(actor, readRegistration(c));
                if (r != null) {
                    list.add(r);
                }
            }
        } finally {
            c.close();
        }
        return list;
    }

    /**
     * Decides what one person may see of a registration. This is done here, in the data
     * layer, so no screen can show more by mistake.
     *   Faculty, Core, Asoo Core: everything.
     *   A Coach: everything in the sports assigned to them; the public view elsewhere.
     *   A captain: everything about their own branch; the public view of other branches.
     *   The public: approved registrations only, without the phone number.
     */
    private static Registration forViewer(User actor, Registration r) {
        if (AccessControl.canViewPrivateRegistrationData(
                AccessControl.inSport(actor, r.sportId), r.branchId)) {
            return r;
        }
        if (!r.isApproved()) {
            return null;
        }
        r.contactPhone = "";
        return r;
    }

    @Override
    public Registration getRegistration(User actorArg, String id) {
        Registration r = rawRegistration(id);
        return r == null ? null : forViewer(trusted(actorArg), r);
    }

    /** The registration exactly as stored. Only for permission checks inside this class. */
    Registration rawRegistration(String id) {
        if (id == null) {
            return null;
        }
        Cursor c = query(REG_SELECT + "WHERE r.id = ?", id);
        try {
            return c.moveToFirst() ? readRegistration(c) : null;
        } finally {
            c.close();
        }
    }

    @Override
    public String saveRegistration(User actorArg, Registration registration) {
        User actor = actorIn(actorArg,
                registration.id == null ? sportOfCategory(registration.categoryId) : sportOfRegistration(registration.id));
        boolean isNew = registration.id == null;
        Registration existing = isNew ? null : rawRegistration(registration.id);
        if (!isNew && existing == null) {
            throw new ValidationException("This registration no longer exists.");
        }

        // Who may do this: Faculty and Core for any branch; a captain only for their own
        // branch, and only while the registration has not been approved.
        if (!isNew) {
            AccessControl.require(AccessControl.canEditRegistration(actor, existing), actor,
                    "edit this registration");
        }
        AccessControl.require(AccessControl.canRegisterForBranch(actor, registration.branchId),
                actor, "register for this branch");

        Category category = getCategory(isNew ? registration.categoryId : existing.categoryId);
        if (category == null) {
            throw new ValidationException("The category of this registration no longer exists.");
        }
        Branch branch = getBranch(registration.branchId);
        if (branch == null) {
            throw new ValidationException("Choose the branch.");
        }
        boolean branchChanged = isNew || !branch.id.equals(existing.branchId);
        if (branchChanged && !branch.active) {
            throw new ValidationException("This branch is deactivated. Choose an active branch.");
        }
        if (registration.year < Registration.MIN_YEAR || registration.year > Registration.MAX_YEAR) {
            throw new ValidationException("Choose the academic year (1 to 4).");
        }

        // The name: built from branch and year for a team, typed for an individual.
        String label = "";
        String name;
        if (category.isTeam()) {
            label = clean(registration.teamLabel);
            name = Registration.teamName(branch.name, registration.year, label);
        } else {
            name = clean(registration.name);
            if (name.isEmpty()) {
                throw new ValidationException("Participant name is required.");
            }
        }

        // Tidy the member list: one name per line, no blanks, no repeats.
        String members = "";
        if (category.isTeam()) {
            List<String> names = new ArrayList<>();
            for (String part : clean(registration.members).split("[\\n,]")) {
                String member = part.trim();
                if (member.isEmpty()) {
                    continue;
                }
                for (String already : names) {
                    if (already.equalsIgnoreCase(member)) {
                        throw new ValidationException("\"" + member
                                + "\" is listed more than once in this team.");
                    }
                }
                names.add(member);
            }
            if (names.size() < category.minSize || names.size() > category.maxSize) {
                throw new ValidationException("This category needs " + category.sizeText()
                        + " per team. You listed " + names.size() + ".");
            }
            StringBuilder sb = new StringBuilder();
            for (String member : names) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(member);
            }
            members = sb.toString();
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        String id = isNew ? newId() : registration.id;
        if (count(db, "registrations", "category_id = ? AND name = ? AND id <> ?",
                category.id, name, id) > 0) {
            throw new ValidationException(category.isTeam()
                    ? "This branch already has a team for that year in this category. Give "
                    + "the second team a label such as \"Team B\"."
                    : "\"" + name + "\" is already registered in this category.");
        }
        if (isNew && !category.deadline.isEmpty()
                && DateUtil.today().compareTo(category.deadline) > 0) {
            throw new ValidationException("The registration deadline (" + category.deadline
                    + ") has passed. Extend the deadline in the category settings first.");
        }

        // Limits of the category. Rejected registrations never count. They are checked
        // for a new entry and when an entry moves to another branch or year, so an
        // existing entry can always be corrected.
        RuleSet limits = category.ruleSet();
        boolean placeChanged = branchChanged || existing.year != registration.year;
        if (category.isTeam() && placeChanged) {
            int allowed = Math.max(1, limits.getInt(RuleSet.TEAMS_PER_BRANCH, 1));
            int entered = count(db, "registrations", "category_id = ? AND branch_id = ? "
                            + "AND year = ? AND status <> 'REJECTED' AND id <> ?", category.id,
                    branch.id, String.valueOf(registration.year), id);
            if (entered >= allowed) {
                throw new ValidationException(allowed == 1
                        ? branch.name + " already has a team for Year " + registration.year
                        + " in this category, and the category allows one team per branch "
                        + "and year."
                        : branch.name + " already has " + entered + " teams for Year "
                        + registration.year + " in this category, which is the most the "
                        + "category allows.");
            }
            if (entered > 0 && label.isEmpty()) {
                throw new ValidationException("This is an additional team of " + branch.name
                        + ". Give it a label such as \"Team B\".");
            }
        }
        int capacity = Math.max(0, limits.getInt(RuleSet.MAX_ENTRIES, 0));
        if (isNew && capacity > 0 && count(db, "registrations",
                "category_id = ? AND status <> 'REJECTED'", category.id) >= capacity) {
            throw new ValidationException("This category is full: it accepts " + capacity
                    + " entries.");
        }

        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("branch_id", branch.id);
        cv.put("year", registration.year);
        cv.put("team_label", label);
        cv.put("logo_path", clean(registration.logoPath));
        cv.put("contact_name", clean(registration.contactName));
        cv.put("contact_phone", clean(registration.contactPhone));
        cv.put("members", members);
        if (isNew) {
            cv.put("id", id);
            cv.put("category_id", category.id);
            cv.put("status", Registration.STATUS_PENDING);
            cv.put("created_at", System.currentTimeMillis());
            try {
                db.insertOrThrow("registrations", null, cv);
            } catch (android.database.sqlite.SQLiteConstraintException e) {
                throw new ValidationException("\"" + name + "\" is already registered in "
                        + "this category.");
            }
        } else {
            // A captain who edits a rejected registration is submitting it again.
            if (AccessControl.isCaptain(actor)
                    && Registration.STATUS_REJECTED.equals(existing.status)) {
                cv.put("status", Registration.STATUS_PENDING);
            }
            db.update("registrations", cv, "id = ?", new String[]{id});
        }
        return id;
    }

    @Override
    public void setRegistrationLogo(User actorArg, String id, String logoPath) {
        User actor = actorIn(actorArg, sportOfRegistration(id));
        Registration existing = rawRegistration(id);
        if (existing == null) {
            throw new ValidationException("This registration no longer exists.");
        }
        AccessControl.require(AccessControl.canSetLogo(actor, existing), actor,
                "change the logo of this team");
        ContentValues cv = new ContentValues();
        cv.put("logo_path", clean(logoPath));
        helper.getWritableDatabase().update("registrations", cv, "id = ?", new String[]{id});
    }

    @Override
    public void setRegistrationStatus(User actorArg, String id, String status) {
        User actor = actorIn(actorArg, sportOfRegistration(id));
        AccessControl.require(AccessControl.canManageRegistrations(actor), actor,
                "approve or reject registrations");

        if (!Registration.isValidStatus(status)) {
            throw new ValidationException("Unknown registration status.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (!Registration.STATUS_APPROVED.equals(status) && isUsedInMatches(db, id)) {
            throw new ValidationException("This registration already has fixtures, so it must "
                    + "stay approved. Delete its fixtures first.");
        }
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        if (db.update("registrations", cv, "id = ?", new String[]{id}) == 0) {
            throw new ValidationException("This registration no longer exists.");
        }
    }

    @Override
    public void deleteRegistration(User actorArg, String id) {
        User actor = actorIn(actorArg, sportOfRegistration(id));
        Registration existing = rawRegistration(id);
        if (existing == null) {
            throw new ValidationException("This registration no longer exists.");
        }
        AccessControl.require(AccessControl.canEditRegistration(actor, existing), actor,
                "delete this registration");

        SQLiteDatabase db = helper.getWritableDatabase();
        if (isUsedInMatches(db, id)) {
            throw new ValidationException("This registration has fixtures. "
                    + "Delete its fixtures first.");
        }
        db.delete("registrations", "id = ?", new String[]{id});
    }

    private boolean isUsedInMatches(SQLiteDatabase db, String registrationId) {
        return count(db, "matches", "team_a_id = ? OR team_b_id = ?",
                registrationId, registrationId) > 0
                || count(db, "event_entries", "registration_id = ?", registrationId) > 0;
    }

    // =====================================================================
    // Branches
    // =====================================================================

    @Override
    public Branch getBranch(String id) {
        if (id == null) {
            return null;
        }
        for (Branch b : getBranches()) {
            if (b.id.equals(id)) {
                return b;
            }
        }
        return null;
    }

    @Override
    public String saveBranch(User actorArg, Branch branch) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageBranches(actor), actor,
                "add or edit branches");

        String name = clean(branch.name);
        String code = clean(branch.code).toUpperCase();
        if (name.isEmpty()) {
            throw new ValidationException("Branch name is required.");
        }
        if (code.isEmpty() || code.length() > 8) {
            throw new ValidationException("Give the branch a short code of 1 to 8 characters, "
                    + "for example CE.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        boolean isNew = branch.id == null;
        String id = isNew ? newId() : branch.id;
        if (count(db, "branches", "name = ? AND id <> ?", name, id) > 0) {
            throw new ValidationException("A branch named \"" + name + "\" already exists.");
        }

        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("code", code);
        db.beginTransaction();
        try {
            if (isNew) {
                cv.put("id", id);
                cv.put("active", 1);
                db.insertOrThrow("branches", null, cv);
            } else {
                if (db.update("branches", cv, "id = ?", new String[]{id}) == 0) {
                    throw new ValidationException("This branch no longer exists.");
                }
                // Team names contain the branch name, so they are rebuilt after a rename.
                Cursor c = db.rawQuery("SELECT r.id, r.year, r.team_label FROM registrations r "
                        + "JOIN categories c ON c.id = r.category_id "
                        + "WHERE r.branch_id = ? AND c.type = 'TEAM'", new String[]{id});
                try {
                    while (c.moveToNext()) {
                        ContentValues team = new ContentValues();
                        team.put("name", Registration.teamName(name, c.getInt(1), c.getString(2)));
                        db.update("registrations", team, "id = ?", new String[]{c.getString(0)});
                    }
                } finally {
                    c.close();
                }
            }
            DbHelper.writeAdminAudit(db, actor.id, actor.name,
                    isNew ? "BRANCH_ADDED" : "BRANCH_EDITED", name + " (" + code + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return id;
    }

    @Override
    public void setBranchActive(User actorArg, String id, boolean active) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageBranches(actor), actor,
                (active ? "reactivate" : "deactivate") + " branches");
        Branch branch = getBranch(id);
        if (branch == null) {
            throw new ValidationException("This branch no longer exists.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (!active && count(db, "users", "role = ? AND active = 1 AND branch_id = ?",
                Role.CAPTAIN, id) > 0) {
            throw new ValidationException("This branch still has an active Branch CR. "
                    + "Deactivate or reassign the Branch CR first.");
        }
        ContentValues cv = new ContentValues();
        cv.put("active", active ? 1 : 0);
        db.update("branches", cv, "id = ?", new String[]{id});
        audit(actor, active ? "BRANCH_REACTIVATED" : "BRANCH_DEACTIVATED", branch.name);
    }

    // =====================================================================
    // Fixtures and scores: see MatchStore
    // =====================================================================

    @Override
    public List<Match> getMatches(String categoryId) {
        return matches.getMatches(categoryId);
    }

    @Override
    public List<Match> getMatchesOfBranch(String branchId) {
        return matches.getMatchesOfBranch(branchId);
    }

    @Override
    public Match getMatch(String id) {
        return matches.getMatch(id);
    }

    @Override
    public String saveFixture(User actorArg, Match match) {
        return matches.saveFixture(actorArg, match);
    }

    @Override
    public void deleteFixture(User actorArg, String id) {
        matches.deleteFixture(actorArg, id);
    }

    @Override
    public void setMatchStatus(User actorArg, String matchId, int expectedVersion, String status) {
        matches.setMatchStatus(actorArg, matchId, expectedVersion, status);
    }

    @Override
    public void updateScore(User actorArg, String matchId, int expectedVersion, Integer scoreA,
            Integer scoreB, String note, String status) {
        matches.updateScore(actorArg, matchId, expectedVersion, scoreA, scoreB, note, status);
    }

    @Override
    public void updateScore(User actorArg, String matchId, int expectedVersion, Integer scoreA,
            Integer scoreB, String note, String status, int currentPeriod, Integer penA, Integer penB) {
        matches.updateScore(actorArg, matchId, expectedVersion, scoreA, scoreB, note, status, currentPeriod, penA, penB);
    }

    @Override
    public List<MatchPeriod> getPeriods(String matchId) {
        return matches.getPeriods(matchId);
    }

    @Override
    public void saveSetScore(User actorArg, String matchId, int expectedVersion, int setNumber,
            int scoreA, int scoreB, int tieBreakA, int tieBreakB) {
        matches.saveSetScore(actorArg, matchId, expectedVersion, setNumber, scoreA, scoreB, tieBreakA, tieBreakB);
    }

    @Override
    public void scoreTennisPoint(User actorArg, String matchId, int expectedVersion, String side) {
        matches.scoreTennisPoint(actorArg, matchId, expectedVersion, side);
    }

    @Override
    public void saveInnings(User actorArg, String matchId, int expectedVersion, int number,
            String side, int runs, int wickets, int overs, int ballsInOver, int extras) {
        matches.saveInnings(actorArg, matchId, expectedVersion, number, side, runs, wickets, overs, ballsInOver, extras);
    }

    @Override
    public List<MatchEvent> getEvents(String matchId) {
        return matches.getEvents(matchId);
    }

    @Override
    public String addEvent(User actorArg, String matchId, int minute, String side, String type,
            String player) {
        return matches.addEvent(actorArg, matchId, minute, side, type, player);
    }

    @Override
    public void deleteEvent(User actorArg, String eventId) {
        matches.deleteEvent(actorArg, eventId);
    }

    @Override
    public void finaliseMatch(User actorArg, String matchId, int expectedVersion) {
        matches.finaliseMatch(actorArg, matchId, expectedVersion);
    }

    @Override
    public void reopenMatch(User actorArg, String matchId, int expectedVersion, String reason) {
        matches.reopenMatch(actorArg, matchId, expectedVersion, reason);
    }

    @Override
    public void correctFinalResult(User actorArg, String matchId, int expectedVersion,
            Integer scoreA, Integer scoreB, String note, String reason) {
        matches.correctFinalResult(actorArg, matchId, expectedVersion, scoreA, scoreB, note, reason);
    }

    @Override
    public List<ScoreAudit> getScoreAudit(User actorArg, String matchId) {
        return matches.getScoreAudit(actorArg, matchId);
    }

    // =====================================================================
    // Heats and finals: see EventStore
    // =====================================================================

    @Override
    public List<EventRound> getRounds(String categoryId) {
        return events.getRounds(categoryId);
    }

    @Override
    public EventRound getRound(String id) {
        return events.getRound(id);
    }

    @Override
    public String saveRound(User actorArg, EventRound round) {
        return events.saveRound(actorArg, round);
    }

    @Override
    public void deleteRound(User actorArg, String id) {
        events.deleteRound(actorArg, id);
    }

    @Override
    public List<EventEntry> getEntries(String roundId) {
        return events.getEntries(roundId);
    }

    @Override
    public String addEntry(User actorArg, String roundId, String registrationId, int lane) {
        return events.addEntry(actorArg, roundId, registrationId, lane);
    }

    @Override
    public void removeEntry(User actorArg, String entryId) {
        events.removeEntry(actorArg, entryId);
    }

    @Override
    public void setRoundStatus(User actorArg, String roundId, int expectedVersion, String status) {
        events.setRoundStatus(actorArg, roundId, expectedVersion, status);
    }

    @Override
    public void saveTimedResult(User actorArg, String entryId, int expectedVersion,
            Integer timeMs, String resultStatus) {
        events.saveTimedResult(actorArg, entryId, expectedVersion, timeMs, resultStatus);
    }

    @Override
    public void saveAttempt(User actorArg, String entryId, int expectedVersion, int number,
            Integer distanceCm, boolean foul) {
        events.saveAttempt(actorArg, entryId, expectedVersion, number, distanceCm, foul);
    }

    @Override
    public void setEntryStatus(User actorArg, String entryId, int expectedVersion,
            String resultStatus) {
        events.setEntryStatus(actorArg, entryId, expectedVersion, resultStatus);
    }

    @Override
    public void finaliseRound(User actorArg, String roundId, int expectedVersion) {
        events.finaliseRound(actorArg, roundId, expectedVersion);
    }

    @Override
    public void reopenRound(User actorArg, String roundId, int expectedVersion, String reason) {
        events.reopenRound(actorArg, roundId, expectedVersion, reason);
    }

    @Override
    public List<ScoreAudit> getRoundAudit(User actorArg, String roundId) {
        return events.getRoundAudit(actorArg, roundId);
    }

    @Override
    public String createFinalFromHeats(User actorArg, String categoryId) {
        return events.createFinalFromHeats(actorArg, categoryId);
    }

    // =====================================================================
    // Standings and statistics
    // =====================================================================

    @Override
    public List<StandingRow> getStandings(String categoryId) {
        Category category = getCategory(categoryId);
        if (category == null) {
            return new ArrayList<>();
        }
        // Passing null as the actor returns approved registrations only.
        return StandingsCalculator.calculate(category, getRegistrations(null, categoryId),
                getMatches(categoryId));
    }

    // ----- Medals -----

    @Override
    public List<MedalWin> getMedals(String categoryId) {
        List<MedalWin> wins = new ArrayList<>();
        for (Sport sport : getSports()) {
            for (Category category : getCategories(sport.id)) {
                if (categoryId == null || categoryId.equals(category.id)) {
                    addMedals(wins, category);
                }
            }
        }
        return wins;
    }

    @Override
    public List<MedalRow> getMedalTable() {
        return Medals.table(getMedals(null), getBranches());
    }

    /**
     * Adds the medals of one category. Nothing is stored: the medals follow from the
     * official results, in a way that depends on how the category is played.
     *
     *   Race or throw   positions in the final, once it has been made official
     *   Knockout        winner and loser of the fixture named "Final"; bronze goes to
     *                   the winner of a "Third place" fixture, if one is played
     *   League          the first places of the points table, once every fixture is final
     */
    private void addMedals(List<MedalWin> wins, Category category) {
        int medalCount = Medals.countFor(category.ruleSet().get("medals", ""));
        if (medalCount == 0) {
            return;
        }
        List<MedalWin> found = new ArrayList<>();
        if (SportTemplate.ENGINE_TIMED.equals(category.engine)
                || SportTemplate.ENGINE_MEASURED.equals(category.engine)) {
            for (EventRound round : getRounds(category.id)) {
                if (round.isHeat() || !round.isFinal) {
                    continue;
                }
                for (EventEntry e : getEntries(round.id)) {
                    if (e.position != null) {
                        addMedal(found, e.registrationId,
                                Medals.forPosition(e.position, medalCount));
                    }
                }
            }
        } else if (category.isKnockout()) {
            for (Match m : getMatches(category.id)) {
                int winner = m.isFinal && m.hasScore() ? m.winnerSide() : 0;
                if (winner == 0) {
                    continue;
                }
                String winnerId = winner == 1 ? m.teamAId : m.teamBId;
                String loserId = winner == 1 ? m.teamBId : m.teamAId;
                if (Medals.isFinalRound(m.round)) {
                    addMedal(found, winnerId, Medals.forPosition(1, medalCount));
                    addMedal(found, loserId, Medals.forPosition(2, medalCount));
                } else if (Medals.isThirdPlaceRound(m.round)) {
                    addMedal(found, winnerId, Medals.forPosition(3, medalCount));
                }
            }
        } else {
            List<Match> matches = getMatches(category.id);
            boolean complete = !matches.isEmpty();
            for (Match m : matches) {
                complete = complete && m.isFinal;
            }
            if (complete) {
                List<StandingRow> table = getStandings(category.id);
                for (int i = 0; i < table.size() && i < 3; i++) {
                    if (table.get(i).played > 0) {
                        addMedal(found, table.get(i).registrationId,
                                Medals.forPosition(i + 1, medalCount));
                    }
                }
            }
        }
        java.util.Collections.sort(found, new java.util.Comparator<MedalWin>() {
            @Override
            public int compare(MedalWin x, MedalWin y) {
                return Medals.order(x.medal) - Medals.order(y.medal);
            }
        });
        wins.addAll(found);
    }

    /** Adds a medal for a registration; "medal" is null when the position earns none. */
    private void addMedal(List<MedalWin> wins, String registrationId, String medal) {
        Registration winner = medal == null ? null : rawRegistration(registrationId);
        if (winner == null) {
            return;
        }
        MedalWin win = new MedalWin();
        win.medal = medal;
        win.categoryId = winner.categoryId;
        win.categoryName = winner.categoryName;
        win.sportName = winner.sportName;
        win.registrationId = winner.id;
        win.name = winner.name;
        win.team = winner.memberCount() > 0;
        win.year = winner.year;
        win.branchId = winner.branchId;
        win.branchName = winner.branchName;
        win.branchCode = winner.branchCode;
        wins.add(win);
    }

    @Override
    public DashboardStats getStats(User actorArg) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canViewDashboard(actor), actor,
                "view the dashboard");

        SQLiteDatabase db = helper.getReadableDatabase();
        DashboardStats stats = new DashboardStats();
        stats.sports = count(db, "sports", null);
        stats.categories = count(db, "categories", null);
        stats.registrations = count(db, "registrations", null);
        stats.pendingRegistrations = count(db, "registrations", "status = 'PENDING'");
        stats.approvedRegistrations = count(db, "registrations", "status = 'APPROVED'");
        stats.matches = count(db, "matches", null);
        stats.liveMatches = count(db, "matches", "status = 'LIVE'");
        stats.awaitingFinalisation = count(db, "matches", "status = 'COMPLETED' AND is_final = 0");
        stats.finalResults = count(db, "matches", "is_final = 1");
        stats.users = count(db, "users", null);
        return stats;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Returns the actor as stored in the database. The object passed in by a screen is
     * never trusted for its role: only its id and session are used to look the real
     * record up.
     */
    User trusted(User actor) {
        if (actor == null || actor.id == null) {
            return null;
        }
        // The session must still be live and must belong to this user. A session that
        // was signed out, taken over or left unused too long gives no identity at all,
        // so the person is treated as a member of the public.
        if (!actor.id.equals(sessions.userOfLiveSession(actor.sessionId))) {
            return null;
        }
        User user = getUser(actor.id);
        if (user != null) {
            user.sessionId = actor.sessionId;
        }
        return user;
    }

    private Cursor query(String sql, String... args) {
        return helper.getReadableDatabase().rawQuery(sql, args);
    }

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
