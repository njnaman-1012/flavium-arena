package com.example.flaviumarena.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
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
import com.example.flaviumarena.scoring.EventRanking;
import com.example.flaviumarena.scoring.LiveLink;
import com.example.flaviumarena.scoring.Measure;
import com.example.flaviumarena.scoring.Medals;
import com.example.flaviumarena.scoring.SetRules;
import com.example.flaviumarena.scoring.SetScoring;
import com.example.flaviumarena.scoring.TennisGame;
import com.example.flaviumarena.util.DateUtil;
import com.example.flaviumarena.util.PasswordUtil;
import com.example.flaviumarena.util.StandingsCalculator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    public SqliteArenaRepository(DbHelper helper) {
        this.helper = helper;
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
    @Override
    public User login(String email, String password, String expectedRole) {
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
                user = readUser(c);
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
            return c.moveToFirst() ? readUser(c) : null;
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
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "view the list of Asoo Cores");
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
        db.update("users", cv, "id = ?", new String[]{userId});
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
        db.update("users", cv, "id = ?", new String[]{userId});
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
        db.insertOrThrow("users", null, cv);
        return id;
    }

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
        User actor = trusted(actorArg);
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
        User actor = trusted(actorArg);
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
    private static final String REG_SELECT =
            "SELECT r.*, b.name AS branch_name, b.code AS branch_code, "
                    + "c.name AS category_name, s.name AS sport_name "
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
     *   A captain: everything about their own branch; the public view of other branches.
     *   The public: approved registrations only, without the phone number.
     */
    private static Registration forViewer(User actor, Registration r) {
        if (AccessControl.canViewPrivateRegistrationData(actor, r.branchId)) {
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
    private Registration rawRegistration(String id) {
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
        User actor = trusted(actorArg);
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
            db.insertOrThrow("registrations", null, cv);
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
        User actor = trusted(actorArg);
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
        User actor = trusted(actorArg);
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
        User actor = trusted(actorArg);
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
    // Fixtures and scores
    // =====================================================================

    /** Reads a match together with the names needed to display it. */
    private static final String MATCH_SELECT =
            "SELECT m.*, ra.name AS team_a_name, rb.name AS team_b_name, "
                    + "ra.year AS year_a, rb.year AS year_b, "
                    + "ra.logo_path AS logo_a, rb.logo_path AS logo_b, "
                    + "ba.name AS branch_a_name, ba.code AS branch_a_code, "
                    + "bb.name AS branch_b_name, bb.code AS branch_b_code, "
                    + "c.type AS category_type, c.engine AS category_engine, "
                    + "c.rules AS category_rules, "
                    + "c.name AS category_name, c.format AS category_format, "
                    + "s.name AS sport_name, ua.name AS assigned_name, "
                    + "ub.name AS updated_by_name "
                    + "FROM matches m "
                    + "JOIN registrations ra ON ra.id = m.team_a_id "
                    + "JOIN registrations rb ON rb.id = m.team_b_id "
                    + "LEFT JOIN branches ba ON ba.id = ra.branch_id "
                    + "LEFT JOIN branches bb ON bb.id = rb.branch_id "
                    + "JOIN categories c ON c.id = m.category_id "
                    + "JOIN sports s ON s.id = c.sport_id "
                    + "LEFT JOIN users ua ON ua.id = m.assigned_to "
                    + "LEFT JOIN users ub ON ub.id = m.updated_by ";

    private static final String MATCH_ORDER = " ORDER BY m.match_date, m.match_time, m.round";

    @Override
    public List<Match> getMatches(String categoryId) {
        Cursor c;
        if (categoryId == null) {
            c = query(MATCH_SELECT + MATCH_ORDER);
        } else {
            c = query(MATCH_SELECT + "WHERE m.category_id = ?" + MATCH_ORDER, categoryId);
        }
        List<Match> list = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                list.add(readMatch(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** Every match in which a team or participant of this branch plays. */
    @Override
    public List<Match> getMatchesOfBranch(String branchId) {
        List<Match> list = new ArrayList<>();
        Cursor c = query(MATCH_SELECT + "WHERE ra.branch_id = ? OR rb.branch_id = ?"
                + MATCH_ORDER, branchId, branchId);
        try {
            while (c.moveToNext()) {
                list.add(readMatch(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    @Override
    public Match getMatch(String id) {
        if (id == null) {
            return null;
        }
        Cursor c = query(MATCH_SELECT + "WHERE m.id = ?", id);
        try {
            return c.moveToFirst() ? readMatch(c) : null;
        } finally {
            c.close();
        }
    }

    @Override
    public String saveFixture(User actorArg, Match match) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "create or edit fixtures");

        Category category = getCategory(match.categoryId);
        if (category == null) {
            throw new ValidationException("The category of this fixture no longer exists.");
        }
        if (match.teamAId == null || match.teamBId == null) {
            throw new ValidationException("Choose both sides of the fixture.");
        }
        if (match.teamAId.equals(match.teamBId)) {
            throw new ValidationException("A team cannot play against itself.");
        }
        checkSide(match.teamAId, category);
        checkSide(match.teamBId, category);

        String date = clean(match.date);
        String time = clean(match.time);
        String round = clean(match.round);
        if (!DateUtil.isValidDate(date)) {
            throw new ValidationException("Choose the match date.");
        }
        if (!time.isEmpty() && !DateUtil.isValidTime(time)) {
            throw new ValidationException("The match time must look like 16:30.");
        }
        if (round.isEmpty()) {
            throw new ValidationException("Round or stage is required.");
        }
        if (match.assignedTo != null) {
            User assignee = getUser(match.assignedTo);
            if (assignee == null || !AccessControl.isAsooCore(assignee)) {
                throw new ValidationException("A match can only be assigned to an Asoo Core.");
            }
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("team_a_id", match.teamAId);
        cv.put("team_b_id", match.teamBId);
        cv.put("match_date", date);
        cv.put("match_time", time);
        cv.put("venue", clean(match.venue));

        // The CricHeroes link: optional, cricket only, and it must be a CricHeroes address.
        String link = clean(match.externalUrl);
        if (!link.isEmpty()) {
            if (!SportTemplate.ENGINE_CRICKET.equals(category.engine)) {
                throw new ValidationException("A live-score link can only be added to a "
                        + "cricket fixture.");
            }
            String problem = LiveLink.problemWith(link);
            if (problem != null) {
                throw new ValidationException(problem);
            }
        }
        cv.put("external_url", link);
        cv.put("round", round);
        if (match.assignedTo == null) {
            cv.putNull("assigned_to");
        } else {
            cv.put("assigned_to", match.assignedTo);
        }
        cv.put("updated_at", System.currentTimeMillis());
        cv.put("updated_by", actor.id);

        if (match.id == null) {
            String id = newId();
            cv.put("id", id);
            cv.put("category_id", category.id);
            cv.put("status", Match.STATUS_UPCOMING);
            cv.put("is_final", 0);
            cv.put("version", 1);
            db.insertOrThrow("matches", null, cv);
            return id;
        }

        Match existing = requireMatch(match.id);
        boolean sidesChanged = !existing.teamAId.equals(match.teamAId)
                || !existing.teamBId.equals(match.teamBId);
        if (sidesChanged && existing.hasScore()) {
            throw new ValidationException("This match already has a score, so its teams "
                    + "cannot be changed.");
        }
        cv.put("version", match.version + 1);
        int rows = db.update("matches", cv, "id = ? AND version = ?",
                new String[]{match.id, String.valueOf(match.version)});
        if (rows == 0) {
            throw new ConflictException("Someone else changed this match while you were "
                    + "editing it. Your changes were not saved. Open it again and retry.");
        }
        return match.id;
    }

    /** A side of a fixture must be an approved registration of the same category. */
    private void checkSide(String registrationId, Category category) {
        Cursor c = query(REG_SELECT + "WHERE r.id = ?", registrationId);
        try {
            if (!c.moveToFirst()) {
                throw new ValidationException("One of the chosen sides no longer exists.");
            }
            Registration r = readRegistration(c);
            if (!category.id.equals(r.categoryId)) {
                throw new ValidationException(r.name + " is not registered in this category.");
            }
            if (!r.isApproved()) {
                throw new ValidationException(r.name + " is not approved yet.");
            }
        } finally {
            c.close();
        }
    }

    @Override
    public void deleteFixture(User actorArg, String id) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageFixtures(actor), actor, "delete fixtures");
        // The score history of the match is removed with it (ON DELETE CASCADE).
        helper.getWritableDatabase().delete("matches", "id = ?", new String[]{id});
    }

    // ----- Who may score, and when -----

    /**
     * Common checks before any score is written: the actor may score this match, the
     * result is not final, and the match has actually started.
     */
    private void requireScoring(User actor, Match match) {
        AccessControl.require(AccessControl.canUpdateScore(actor, match), actor,
                "update the score of this match");
        if (match.isFinal) {
            throw new ValidationException("This result is final. It has to be reopened with "
                    + "a reason before the score can change.");
        }
        if (!Match.STATUS_LIVE.equals(match.status)
                && !Match.STATUS_COMPLETED.equals(match.status)) {
            throw new ValidationException("Scores can only be entered while the match is "
                    + "Live. Its status is " + match.status + ": start or resume it first.");
        }
    }

    private void requireEngine(Match match, String engine, String what) {
        if (!match.usesEngine(engine)) {
            throw new ValidationException("This category is not scored " + what + ".");
        }
    }

    // ----- Status -----

    /** Start, pause, resume, postpone or complete a match without touching its score. */
    @Override
    public void setMatchStatus(User actorArg, String matchId, int expectedVersion,
                               String status) {
        User actor = trusted(actorArg);
        Match match = requireMatch(matchId);
        AccessControl.require(AccessControl.canUpdateScore(actor, match), actor,
                "change the status of this match");
        if (match.isFinal) {
            throw new ValidationException("This result is final, so its status cannot change.");
        }
        if (!Match.isValidStatus(status)) {
            throw new ValidationException("Choose a valid match status.");
        }
        if (Match.STATUS_COMPLETED.equals(status)) {
            checkCanComplete(match);
        }
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_STATUS,
                describe(match.scoreA, match.scoreB, status), "");
    }

    /** A match can be marked completed only when it has a result. */
    private void checkCanComplete(Match match) {
        if (!match.hasScore()) {
            throw new ValidationException("Enter the score before marking the match as "
                    + "completed.");
        }
        if (match.usesEngine(SportTemplate.ENGINE_SETS)
                && setState(match, getPeriods(match.id)).winner == SetScoring.NOBODY) {
            throw new ValidationException("This match is not decided yet: neither side has "
                    + "won enough " + SetRules.from(match.rules()).unit.toLowerCase() + "s.");
        }
    }

    // ----- One score for each side: football, basketball; and win/draw/loss: chess -----

    @Override
    public void updateScore(User actorArg, String matchId, int expectedVersion,
                            Integer scoreA, Integer scoreB, String note, String status) {
        Match current = requireMatch(matchId);
        updateScore(actorArg, matchId, expectedVersion, scoreA, scoreB, note, status,
                current.currentPeriod, current.penA, current.penB);
    }

    @Override
    public void updateScore(User actorArg, String matchId, int expectedVersion,
                            Integer scoreA, Integer scoreB, String note, String status,
                            int currentPeriod, Integer penA, Integer penB) {
        User actor = trusted(actorArg);
        Match match = requireMatch(matchId);
        AccessControl.require(AccessControl.canUpdateScore(actor, match), actor,
                "update the score of this match");

        // An ordinary update must never overwrite a final result.
        if (match.isFinal) {
            throw new ValidationException("This result is final. Use \"Correct final result\" "
                    + "and give a reason.");
        }
        boolean result = match.usesEngine(SportTemplate.ENGINE_RESULT);
        if (!result && !match.usesEngine(SportTemplate.ENGINE_SCORE)) {
            throw new ValidationException("This category has its own way of scoring. Use the "
                    + "scoring controls shown for it.");
        }
        if (!Match.isValidStatus(status)) {
            throw new ValidationException("Choose a valid match status.");
        }
        checkScores(scoreA, scoreB, Match.STATUS_COMPLETED.equals(status));
        if (scoreA != null && (Match.STATUS_UPCOMING.equals(status)
                || Match.STATUS_POSTPONED.equals(status))) {
            throw new ValidationException("A match that is " + status.toLowerCase()
                    + " cannot have a score. Set it to Live first.");
        }
        if (result && scoreA != null && !isResultScore(scoreA, scoreB)) {
            throw new ValidationException("Choose a result: a win for one side, or a draw.");
        }

        RuleSet rules = match.rules();
        int periods = rules.getInt("periods", 0);
        if (currentPeriod < 0 || (periods > 0 && currentPeriod > periods + 2)) {
            throw new ValidationException("That period does not exist in this match.");
        }
        if ((penA == null) != (penB == null)) {
            throw new ValidationException("Enter the penalty score of both sides.");
        }
        if (penA != null) {
            if (penA < 0 || penB < 0) {
                throw new ValidationException("Penalty scores cannot be negative.");
            }
            if (scoreA == null || !scoreA.equals(scoreB)) {
                throw new ValidationException("A penalty shoot-out is only recorded when "
                        + "the score is level.");
            }
        }

        ContentValues cv = new ContentValues();
        putScore(cv, scoreA, scoreB);
        cv.put("score_note", clean(note));
        cv.put("status", status);
        cv.put("current_period", currentPeriod);
        if (penA == null) {
            cv.putNull("pen_a");
            cv.putNull("pen_b");
        } else {
            cv.put("pen_a", penA);
            cv.put("pen_b", penB);
        }
        String newScore = describe(scoreA, scoreB, status)
                + (penA == null ? "" : ", penalties " + penA + " - " + penB);
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_SCORE_UPDATE, newScore, "");
    }

    /** Win/draw/loss results are stored as 1-0, 0-1 and 1-1. */
    private static boolean isResultScore(int a, int b) {
        return (a == 1 && b == 0) || (a == 0 && b == 1) || (a == 1 && b == 1);
    }

    // ----- Sets, games, frames, pulls -----

    @Override
    public List<MatchPeriod> getPeriods(String matchId) {
        List<MatchPeriod> list = new ArrayList<>();
        Cursor c = query("SELECT * FROM match_periods WHERE match_id = ? ORDER BY number",
                matchId);
        try {
            while (c.moveToNext()) {
                MatchPeriod p = new MatchPeriod();
                p.id = text(c, "id");
                p.matchId = text(c, "match_id");
                p.number = number(c, "number");
                p.side = text(c, "side");
                p.scoreA = number(c, "score_a");
                p.scoreB = number(c, "score_b");
                p.wickets = number(c, "wickets");
                p.balls = number(c, "balls");
                p.extras = number(c, "extras");
                p.tbA = number(c, "tb_a");
                p.tbB = number(c, "tb_b");
                p.ptA = number(c, "pt_a");
                p.ptB = number(c, "pt_b");
                list.add(p);
            }
        } finally {
            c.close();
        }
        return list;
    }

    private static SetScoring.State setState(Match match, List<MatchPeriod> periods) {
        List<int[]> sets = new ArrayList<>();
        for (MatchPeriod p : periods) {
            sets.add(new int[]{p.scoreA, p.scoreB});
        }
        return SetScoring.evaluate(SetRules.from(match.rules()), sets);
    }

    /**
     * Saves the score of one set. Used for every point (the screen sends the new total of
     * the set) and for correcting an earlier set. The whole match is then recalculated:
     * sets won by each side, and "Completed" as soon as one side has won enough sets.
     */
    @Override
    public void saveSetScore(User actorArg, String matchId, int expectedVersion, int setNumber,
                             int scoreA, int scoreB, int tieBreakA, int tieBreakB) {
        User actor = trusted(actorArg);
        Match match = requireMatch(matchId);
        requireScoring(actor, match);
        requireEngine(match, SportTemplate.ENGINE_SETS, "in sets");

        List<MatchPeriod> periods = getPeriods(matchId);
        if (setNumber < 1 || setNumber > periods.size() + 1) {
            throw new ValidationException("Score the parts of the match in order.");
        }
        MatchPeriod set = setNumber <= periods.size() ? periods.get(setNumber - 1)
                : newPeriod(matchId, setNumber);
        if (setNumber > periods.size()) {
            periods.add(set);
        }
        set.scoreA = scoreA;
        set.scoreB = scoreB;
        set.tbA = tieBreakA;
        set.tbB = tieBreakB;
        // Editing the totals directly ends any game that was being counted point by point.
        set.ptA = 0;
        set.ptB = 0;
        storeSets(match, expectedVersion, actor, periods, set);
    }

    /**
     * Lawn tennis, point by point. One call is one point won by side "A" or "B". The
     * point is added to the game in play; a won game is added to the set; at the
     * tie-break score the points count towards the tie-break instead.
     */
    @Override
    public void scoreTennisPoint(User actorArg, String matchId, int expectedVersion,
                                 String side) {
        User actor = trusted(actorArg);
        Match match = requireMatch(matchId);
        requireScoring(actor, match);
        requireEngine(match, SportTemplate.ENGINE_SETS, "in sets");
        SetRules rules = SetRules.from(match.rules());
        if (!rules.pointByPoint) {
            throw new ValidationException("This category is not scored point by point.");
        }
        boolean toA = "A".equals(side);
        if (!toA && !"B".equals(side)) {
            throw new ValidationException("Choose which side won the point.");
        }

        List<MatchPeriod> periods = getPeriods(matchId);
        SetScoring.State state = setState(match, periods);
        if (state.error != null) {
            throw new ValidationException(state.error);
        }
        if (state.currentSet == 0) {
            throw new ValidationException("This match is already decided.");
        }
        MatchPeriod set;
        if (state.currentSet <= periods.size()) {
            set = periods.get(state.currentSet - 1);
        } else {
            set = newPeriod(matchId, state.currentSet);
            periods.add(set);
        }

        int n = set.number;
        if (rules.isDeciding(n) && rules.decidingMatchTiebreak) {
            // The deciding "set" is a single tie-break: every point counts directly.
            if (toA) {
                set.scoreA++;
            } else {
                set.scoreB++;
            }
        } else if (rules.tiebreakIn(n) && set.scoreA == rules.tiebreakAt
                && set.scoreB == rules.tiebreakAt) {
            // Tie-break game at, for example, 6-6.
            if (toA) {
                set.tbA++;
            } else {
                set.tbB++;
            }
            int won = TennisGame.tiebreakWinner(set.tbA, set.tbB, rules.tiebreakPoints);
            if (won == SetScoring.SIDE_A) {
                set.scoreA++;
            } else if (won == SetScoring.SIDE_B) {
                set.scoreB++;
            }
        } else {
            // An ordinary game: 0, 15, 30, 40, deuce, advantage.
            if (toA) {
                set.ptA++;
            } else {
                set.ptB++;
            }
            int won = TennisGame.winner(set.ptA, set.ptB, rules.noAd);
            if (won != SetScoring.NOBODY) {
                if (won == SetScoring.SIDE_A) {
                    set.scoreA++;
                } else {
                    set.scoreB++;
                }
                set.ptA = 0;
                set.ptB = 0;
            }
        }
        storeSets(match, expectedVersion, actor, periods, set);
    }

    private static MatchPeriod newPeriod(String matchId, int number) {
        MatchPeriod p = new MatchPeriod();
        p.matchId = matchId;
        p.number = number;
        return p;
    }

    /**
     * Validates the complete list of sets, then saves the changed set and the new match
     * totals together. Nothing is written when the list is not consistent.
     */
    private void storeSets(Match match, int expectedVersion, User actor,
                           List<MatchPeriod> periods, MatchPeriod changed) {
        SetRules rules = SetRules.from(match.rules());
        SetScoring.State state = setState(match, periods);
        if (state.error != null) {
            throw new ValidationException(state.error);
        }
        // A 7-6 set needs the tie-break score that produced it.
        int high = Math.max(changed.scoreA, changed.scoreB);
        int low = Math.min(changed.scoreA, changed.scoreB);
        boolean endedByTiebreak = rules.tiebreakIn(changed.number)
                && high == rules.tiebreakAt + 1 && low == rules.tiebreakAt;
        if (endedByTiebreak) {
            int won = TennisGame.tiebreakWinner(changed.tbA, changed.tbB, rules.tiebreakPoints);
            int expected = changed.scoreA > changed.scoreB ? SetScoring.SIDE_A : SetScoring.SIDE_B;
            if (won != expected) {
                throw new ValidationException("Enter the tie-break score that decided this "
                        + rules.unit.toLowerCase() + ", for example 7-5.");
            }
        } else if (!(rules.tiebreakIn(changed.number) && high == rules.tiebreakAt
                && low == rules.tiebreakAt)) {
            // Tie-break points only exist while a tie-break is being played.
            changed.tbA = 0;
            changed.tbB = 0;
        }

        // A decided match becomes Completed; a correction that undecides it goes back to Live.
        String status = state.winner != SetScoring.NOBODY ? Match.STATUS_COMPLETED
                : Match.STATUS_LIVE;

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues row = new ContentValues();
            row.put("score_a", changed.scoreA);
            row.put("score_b", changed.scoreB);
            row.put("tb_a", changed.tbA);
            row.put("tb_b", changed.tbB);
            row.put("pt_a", changed.ptA);
            row.put("pt_b", changed.ptB);
            if (changed.id == null) {
                row.put("id", newId());
                row.put("match_id", match.id);
                row.put("number", changed.number);
                db.insertOrThrow("match_periods", null, row);
            } else {
                db.update("match_periods", row, "id = ?", new String[]{changed.id});
            }

            ContentValues cv = new ContentValues();
            cv.put("score_a", state.setsA);
            cv.put("score_b", state.setsB);
            cv.put("status", status);
            writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_SCORE_UPDATE,
                    rules.unit + " " + changed.number + ": " + changed.scoreA + " - "
                            + changed.scoreB + "; " + describe(state.setsA, state.setsB, status),
                    "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ----- Cricket innings -----

    /**
     * Saves the summary of one innings. Ball-by-ball scoring is done in CricHeroes; this
     * records the totals so that results and tables work inside FLAVIUM Arena.
     */
    @Override
    public void saveInnings(User actorArg, String matchId, int expectedVersion, int number,
                            String side, int runs, int wickets, int overs, int ballsInOver,
                            int extras) {
        User actor = trusted(actorArg);
        Match match = requireMatch(matchId);
        requireScoring(actor, match);
        requireEngine(match, SportTemplate.ENGINE_CRICKET, "by innings");

        RuleSet rules = match.rules();
        int maxInnings = Math.max(1, rules.getInt("innings_per_side", 1)) * 2;
        int maxOvers = Math.max(1, rules.getInt("overs", 50));
        int maxWickets = Math.max(1, rules.getInt(RuleSet.TEAM_MIN, 11) - 1);
        if (number < 1 || number > maxInnings) {
            throw new ValidationException("This match has " + maxInnings + " innings.");
        }
        if (!"A".equals(side) && !"B".equals(side)) {
            throw new ValidationException("Choose which side batted.");
        }
        if (runs < 0 || extras < 0 || wickets < 0 || overs < 0 || ballsInOver < 0) {
            throw new ValidationException("Innings figures cannot be negative.");
        }
        if (wickets > maxWickets) {
            throw new ValidationException("At most " + maxWickets + " wickets can fall.");
        }
        if (ballsInOver > 5) {
            throw new ValidationException("An over has six balls, so the balls part is 0 to 5.");
        }
        int balls = overs * 6 + ballsInOver;
        if (balls > maxOvers * 6) {
            throw new ValidationException("An innings is limited to " + maxOvers + " overs.");
        }
        if (extras > runs) {
            throw new ValidationException("Extras are part of the total, so they cannot be "
                    + "more than the runs.");
        }

        List<MatchPeriod> periods = getPeriods(matchId);
        if (number > periods.size() + 1) {
            throw new ValidationException("Enter the innings in order.");
        }
        // The two sides bat alternately.
        if (number > 1 && periods.get(number - 2).side.equals(side)) {
            throw new ValidationException("The same side cannot bat in two innings in a row.");
        }
        MatchPeriod innings = number <= periods.size() ? periods.get(number - 1)
                : newPeriod(matchId, number);
        if (number > periods.size()) {
            periods.add(innings);
        }
        innings.side = side;
        innings.scoreA = runs;
        innings.wickets = wickets;
        innings.balls = balls;
        innings.extras = extras;

        int runsA = 0;
        int runsB = 0;
        for (MatchPeriod p : periods) {
            if ("A".equals(p.side)) {
                runsA += p.scoreA;
            } else {
                runsB += p.scoreA;
            }
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues row = new ContentValues();
            row.put("side", side);
            row.put("score_a", runs);
            row.put("wickets", wickets);
            row.put("balls", balls);
            row.put("extras", extras);
            if (innings.id == null) {
                row.put("id", newId());
                row.put("match_id", matchId);
                row.put("number", number);
                db.insertOrThrow("match_periods", null, row);
            } else {
                db.update("match_periods", row, "id = ?", new String[]{innings.id});
            }
            ContentValues cv = new ContentValues();
            cv.put("score_a", runsA);
            cv.put("score_b", runsB);
            writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_SCORE_UPDATE,
                    "Innings " + number + " (side " + side + "): " + runs + "/" + wickets
                            + " in " + innings.oversText() + " overs; "
                            + describe(runsA, runsB, match.status), "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ----- Match events -----

    @Override
    public List<MatchEvent> getEvents(String matchId) {
        List<MatchEvent> list = new ArrayList<>();
        Cursor c = query("SELECT * FROM match_events WHERE match_id = ? "
                + "ORDER BY minute, created_at", matchId);
        try {
            while (c.moveToNext()) {
                MatchEvent e = new MatchEvent();
                e.id = text(c, "id");
                e.matchId = text(c, "match_id");
                e.minute = number(c, "minute");
                e.side = text(c, "side");
                e.type = text(c, "type");
                e.player = text(c, "player");
                e.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                list.add(e);
            }
        } finally {
            c.close();
        }
        return list;
    }

    @Override
    public String addEvent(User actorArg, String matchId, int minute, String side, String type,
                           String player) {
        User actor = trusted(actorArg);
        Match match = requireMatch(matchId);
        requireScoring(actor, match);
        if (!"A".equals(side) && !"B".equals(side)) {
            throw new ValidationException("Choose the side the event belongs to.");
        }
        if (clean(type).isEmpty()) {
            throw new ValidationException("Choose the kind of event.");
        }
        if (minute < 0 || minute > 300) {
            throw new ValidationException("Enter the minute of the match (0 to 300).");
        }
        String id = newId();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("match_id", matchId);
        cv.put("minute", minute);
        cv.put("side", side);
        cv.put("type", clean(type));
        cv.put("player", clean(player));
        cv.put("created_by", actor.id);
        cv.put("created_at", System.currentTimeMillis());
        helper.getWritableDatabase().insertOrThrow("match_events", null, cv);
        return id;
    }

    @Override
    public void deleteEvent(User actorArg, String eventId) {
        User actor = trusted(actorArg);
        String matchId = null;
        Cursor c = query("SELECT match_id FROM match_events WHERE id = ?", eventId);
        try {
            if (c.moveToFirst()) {
                matchId = c.getString(0);
            }
        } finally {
            c.close();
        }
        if (matchId == null) {
            throw new ValidationException("This event no longer exists.");
        }
        requireScoring(actor, requireMatch(matchId));
        helper.getWritableDatabase().delete("match_events", "id = ?", new String[]{eventId});
    }

    // ----- Finalising, reopening and correcting -----

    @Override
    public void finaliseMatch(User actorArg, String matchId, int expectedVersion) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "finalise results");

        Match match = requireMatch(matchId);
        if (match.isFinal) {
            throw new ValidationException("This result is already final.");
        }
        if (!Match.STATUS_COMPLETED.equals(match.status) || !match.hasScore()) {
            throw new ValidationException("Only a completed match with a score can be "
                    + "finalised.");
        }
        checkCanComplete(match);
        checkHasWinnerIfNeeded(match, match.scoreA, match.scoreB);

        ContentValues cv = new ContentValues();
        cv.put("is_final", 1);
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_FINALISED,
                match.scoreText() + " (FINAL)", "");
    }

    /**
     * Takes a final result back to "completed, not final" so that it can be corrected
     * with the normal scoring controls and finalised again. A reason is required and is
     * kept in the score history, so a final result is never changed silently.
     */
    @Override
    public void reopenMatch(User actorArg, String matchId, int expectedVersion, String reason) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "reopen final results");

        Match match = requireMatch(matchId);
        if (!match.isFinal) {
            throw new ValidationException("This result is not final, so it does not need "
                    + "reopening.");
        }
        if (clean(reason).isEmpty()) {
            throw new ValidationException("Give a reason for reopening a final result.");
        }
        ContentValues cv = new ContentValues();
        cv.put("is_final", 0);
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_REOPENED,
                describe(match.scoreA, match.scoreB, Match.STATUS_COMPLETED), clean(reason));
    }

    @Override
    public void correctFinalResult(User actorArg, String matchId, int expectedVersion,
                                   Integer scoreA, Integer scoreB, String note, String reason) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "correct final results");

        Match match = requireMatch(matchId);
        if (!match.isFinal) {
            throw new ValidationException("This result is not final yet. Update the score "
                    + "normally.");
        }
        boolean result = match.usesEngine(SportTemplate.ENGINE_RESULT);
        if (!result && !match.usesEngine(SportTemplate.ENGINE_SCORE)) {
            throw new ValidationException("Reopen this result with a reason, correct it with "
                    + "the scoring controls, and finalise it again.");
        }
        if (clean(reason).isEmpty()) {
            throw new ValidationException("Give a reason for correcting a final result.");
        }
        checkScores(scoreA, scoreB, true);
        if (result && !isResultScore(scoreA, scoreB)) {
            throw new ValidationException("Choose a result: a win for one side, or a draw.");
        }
        // A corrected score makes any recorded shoot-out meaningless unless still level.
        boolean keepPenalties = scoreA.equals(scoreB) && match.penA != null;
        if (!keepPenalties) {
            match.penA = null;
            match.penB = null;
        }
        checkHasWinnerIfNeeded(match, scoreA, scoreB);

        ContentValues cv = new ContentValues();
        putScore(cv, scoreA, scoreB);
        cv.put("score_note", clean(note));
        if (!keepPenalties) {
            cv.putNull("pen_a");
            cv.putNull("pen_b");
        }
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_CORRECTION,
                scoreA + " - " + scoreB + " (FINAL)", clean(reason));
    }

    /**
     * A knockout match, and any category whose rules say a match may not end in a draw,
     * needs a winner. A level score is accepted only with a decisive penalty shoot-out.
     */
    private static void checkHasWinnerIfNeeded(Match match, Integer scoreA, Integer scoreB) {
        boolean drawForbidden = Category.FORMAT_KNOCKOUT.equals(match.categoryFormat)
                || RuleSet.NO.equals(match.rules().get("allow_draw", ""));
        if (!drawForbidden || !scoreA.equals(scoreB)) {
            return;
        }
        if (match.penA != null && match.penB != null && !match.penA.equals(match.penB)) {
            return;
        }
        throw new ValidationException("This match needs a winner, so the final score cannot "
                + "be a draw. Record the deciding shoot-out or correct the score.");
    }

    /**
     * Saves a change to a match and records it in the score history, as one transaction.
     * The update only succeeds when the row still has the version the screen loaded.
     * If another organiser saved first, nothing is written and a ConflictException is thrown.
     */
    private void writeMatch(Match before, int expectedVersion, ContentValues cv, User actor,
                            String action, String newScore, String reason) {
        long now = System.currentTimeMillis();
        cv.put("version", expectedVersion + 1);
        cv.put("updated_at", now);
        cv.put("updated_by", actor.id);

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            int rows = db.update("matches", cv, "id = ? AND version = ?",
                    new String[]{before.id, String.valueOf(expectedVersion)});
            if (rows == 0) {
                throw new ConflictException("Someone else updated this match a moment ago. "
                        + "Your change was not saved. Check the latest score and try again.");
            }
            ContentValues audit = new ContentValues();
            audit.put("id", newId());
            audit.put("match_id", before.id);
            audit.put("changed_by", actor.id);
            audit.put("changed_at", now);
            audit.put("action", action);
            audit.put("old_score", describe(before.scoreA, before.scoreB, before.statusLabel()));
            audit.put("new_score", newScore);
            audit.put("reason", reason);
            db.insertOrThrow("score_audit", null, audit);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void checkScores(Integer scoreA, Integer scoreB, boolean required) {
        if ((scoreA == null) != (scoreB == null)) {
            throw new ValidationException("Enter the score of both sides.");
        }
        if (scoreA == null) {
            if (required) {
                throw new ValidationException("Enter the score before marking the match "
                        + "as completed.");
            }
            return;
        }
        if (scoreA < 0 || scoreB < 0) {
            throw new ValidationException("Scores cannot be negative.");
        }
    }

    private static void putScore(ContentValues cv, Integer scoreA, Integer scoreB) {
        if (scoreA == null || scoreB == null) {
            cv.putNull("score_a");
            cv.putNull("score_b");
        } else {
            cv.put("score_a", scoreA);
            cv.put("score_b", scoreB);
        }
    }

    private static String describe(Integer scoreA, Integer scoreB, String status) {
        String score = scoreA == null || scoreB == null ? "no score" : scoreA + " - " + scoreB;
        return score + " (" + status + ")";
    }

    private Match requireMatch(String id) {
        Match match = getMatch(id);
        if (match == null) {
            throw new ValidationException("This match no longer exists.");
        }
        return match;
    }

    @Override
    public List<ScoreAudit> getScoreAudit(User actorArg, String matchId) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canViewScoreHistory(actor), actor,
                "view the score history");

        List<ScoreAudit> list = new ArrayList<>();
        Cursor c = query("SELECT a.*, u.name AS changed_by_name FROM score_audit a "
                + "LEFT JOIN users u ON u.id = a.changed_by "
                + "WHERE a.match_id = ? ORDER BY a.changed_at DESC, a.rowid DESC", matchId);
        try {
            while (c.moveToNext()) {
                ScoreAudit a = new ScoreAudit();
                a.id = text(c, "id");
                a.matchId = text(c, "match_id");
                a.changedByName = text(c, "changed_by_name");
                a.changedAt = c.getLong(c.getColumnIndexOrThrow("changed_at"));
                a.action = text(c, "action");
                a.oldScore = text(c, "old_score");
                a.newScore = text(c, "new_score");
                a.reason = text(c, "reason");
                list.add(a);
            }
        } finally {
            c.close();
        }
        return list;
    }

    // =====================================================================
    // Heats and finals: races, relays, swimming, discus, shot put
    // =====================================================================

    private static final String ROUND_SELECT =
            "SELECT r.*, c.name AS category_name, c.engine AS category_engine, "
                    + "c.rules AS category_rules, s.name AS sport_name, "
                    + "ua.name AS assigned_name, ub.name AS updated_by_name, "
                    + "(SELECT COUNT(*) FROM event_entries e WHERE e.round_id = r.id) "
                    + "AS entry_count "
                    + "FROM event_rounds r "
                    + "JOIN categories c ON c.id = r.category_id "
                    + "JOIN sports s ON s.id = c.sport_id "
                    + "LEFT JOIN users ua ON ua.id = r.assigned_to "
                    + "LEFT JOIN users ub ON ub.id = r.updated_by ";

    @Override
    public List<EventRound> getRounds(String categoryId) {
        List<EventRound> list = new ArrayList<>();
        // Heats first, in number order, then the final. Without a category: every
        // heat and final of the festival, by date.
        Cursor c = categoryId == null
                ? query(ROUND_SELECT + "ORDER BY r.event_date, r.event_time, c.name, "
                + "r.stage = 'FINAL', r.heat_number")
                : query(ROUND_SELECT + "WHERE r.category_id = ? "
                + "ORDER BY r.stage = 'FINAL', r.heat_number", categoryId);
        try {
            while (c.moveToNext()) {
                list.add(readRound(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    @Override
    public EventRound getRound(String id) {
        if (id == null) {
            return null;
        }
        Cursor c = query(ROUND_SELECT + "WHERE r.id = ?", id);
        try {
            return c.moveToFirst() ? readRound(c) : null;
        } finally {
            c.close();
        }
    }

    private static EventRound readRound(Cursor c) {
        EventRound r = new EventRound();
        r.id = text(c, "id");
        r.categoryId = text(c, "category_id");
        r.stage = text(c, "stage");
        r.heatNumber = number(c, "heat_number");
        r.date = text(c, "event_date");
        r.time = text(c, "event_time");
        r.venue = text(c, "venue");
        r.status = text(c, "status");
        r.isFinal = number(c, "is_final") == 1;
        r.assignedTo = textOrNull(c, "assigned_to");
        r.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        r.updatedBy = textOrNull(c, "updated_by");
        r.version = number(c, "version");
        r.categoryName = text(c, "category_name");
        r.categoryEngine = text(c, "category_engine");
        r.categoryRules = text(c, "category_rules");
        r.sportName = text(c, "sport_name");
        r.assignedName = text(c, "assigned_name");
        r.updatedByName = text(c, "updated_by_name");
        r.entryCount = number(c, "entry_count");
        return r;
    }

    private EventRound requireRound(String id) {
        EventRound round = getRound(id);
        if (round == null) {
            throw new ValidationException("This heat or final no longer exists.");
        }
        return round;
    }

    @Override
    public String saveRound(User actorArg, EventRound round) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "create or edit heats and finals");

        Category category = getCategory(round.categoryId);
        if (category == null) {
            throw new ValidationException("The category no longer exists.");
        }
        boolean timed = SportTemplate.ENGINE_TIMED.equals(category.engine);
        if (!timed && !SportTemplate.ENGINE_MEASURED.equals(category.engine)) {
            throw new ValidationException("Heats and finals are for races and throwing "
                    + "events. This category uses fixtures between two sides.");
        }
        boolean heat = EventRound.STAGE_HEAT.equals(round.stage);
        if (!heat && !EventRound.STAGE_FINAL.equals(round.stage)) {
            throw new ValidationException("Choose Heat or Final.");
        }
        if (heat && !(timed && category.ruleSet().isYes("heats"))) {
            throw new ValidationException("This category has no heats. Create its "
                    + "competition as the Final.");
        }
        int heatNumber = heat ? round.heatNumber : 0;
        if (heat && heatNumber < 1) {
            throw new ValidationException("Give the heat a number, starting from 1.");
        }
        String date = clean(round.date);
        String time = clean(round.time);
        if (!DateUtil.isValidDate(date)) {
            throw new ValidationException("Choose the date.");
        }
        if (!time.isEmpty() && !DateUtil.isValidTime(time)) {
            throw new ValidationException("The time must look like 16:30.");
        }
        if (round.assignedTo != null) {
            User assignee = getUser(round.assignedTo);
            if (assignee == null || !AccessControl.isAsooCore(assignee)) {
                throw new ValidationException("An event can only be assigned to an Asoo Core.");
            }
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        boolean isNew = round.id == null;
        String id = isNew ? newId() : round.id;
        if (count(db, "event_rounds", "category_id = ? AND stage = ? AND heat_number = ? "
                + "AND id <> ?", category.id, round.stage, String.valueOf(heatNumber), id) > 0) {
            throw new ValidationException(heat ? "Heat " + heatNumber + " already exists."
                    : "This category already has a final.");
        }

        ContentValues cv = new ContentValues();
        cv.put("stage", round.stage);
        cv.put("heat_number", heatNumber);
        cv.put("event_date", date);
        cv.put("event_time", time);
        cv.put("venue", clean(round.venue));
        if (round.assignedTo == null) {
            cv.putNull("assigned_to");
        } else {
            cv.put("assigned_to", round.assignedTo);
        }
        cv.put("updated_at", System.currentTimeMillis());
        cv.put("updated_by", actor.id);
        if (isNew) {
            cv.put("id", id);
            cv.put("category_id", category.id);
            cv.put("status", Match.STATUS_UPCOMING);
            cv.put("is_final", 0);
            cv.put("version", 1);
            db.insertOrThrow("event_rounds", null, cv);
            return id;
        }
        EventRound stored = requireRound(id);
        if (stored.isFinal) {
            throw new ValidationException("These results are official. Reopen them before "
                    + "changing the event.");
        }
        cv.put("version", round.version + 1);
        if (db.update("event_rounds", cv, "id = ? AND version = ?",
                new String[]{id, String.valueOf(round.version)}) == 0) {
            throw new ConflictException("Someone else changed this event while you were "
                    + "editing it. Open it again and retry.");
        }
        return id;
    }

    @Override
    public void deleteRound(User actorArg, String id) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "delete heats and finals");
        // Entrants, attempts and history of the event are removed with it.
        helper.getWritableDatabase().delete("event_rounds", "id = ?", new String[]{id});
    }

    // ----- Entrants -----

    @Override
    public List<EventEntry> getEntries(String roundId) {
        List<EventEntry> list = new ArrayList<>();
        // Placed entrants first in order of position, then the rest by lane.
        Cursor c = query("SELECT e.*, r.name AS reg_name, r.year, r.logo_path, r.members, "
                + "b.name AS branch_name, b.code AS branch_code "
                + "FROM event_entries e "
                + "JOIN registrations r ON r.id = e.registration_id "
                + "LEFT JOIN branches b ON b.id = r.branch_id "
                + "WHERE e.round_id = ? "
                + "ORDER BY e.position IS NULL, e.position, e.lane", roundId);
        try {
            while (c.moveToNext()) {
                EventEntry e = new EventEntry();
                e.id = text(c, "id");
                e.roundId = text(c, "round_id");
                e.registrationId = text(c, "registration_id");
                e.lane = number(c, "lane");
                e.timeMs = numberOrNull(c, "time_ms");
                e.resultStatus = text(c, "result_status");
                e.position = numberOrNull(c, "position");
                e.qualified = number(c, "qualified") == 1;
                e.bestCm = numberOrNull(c, "best_cm");
                e.name = text(c, "reg_name");
                e.year = number(c, "year");
                e.logoPath = text(c, "logo_path");
                e.team = !text(c, "members").isEmpty();
                e.branchName = text(c, "branch_name");
                e.branchCode = text(c, "branch_code");
                list.add(e);
            }
        } finally {
            c.close();
        }
        for (EventEntry e : list) {
            Cursor a = query("SELECT * FROM event_attempts WHERE entry_id = ? ORDER BY number",
                    e.id);
            try {
                while (a.moveToNext()) {
                    EventEntry.Attempt attempt = new EventEntry.Attempt();
                    attempt.number = number(a, "number");
                    attempt.distanceCm = numberOrNull(a, "distance_cm");
                    attempt.foul = number(a, "foul") == 1;
                    e.attempts.add(attempt);
                }
            } finally {
                a.close();
            }
        }
        return list;
    }

    private static EventEntry findEntry(List<EventEntry> entries, String entryId) {
        for (EventEntry e : entries) {
            if (e.id.equals(entryId)) {
                return e;
            }
        }
        throw new ValidationException("This entrant is no longer in the event.");
    }

    /** The heat or final an entrant belongs to. */
    private EventRound roundOfEntry(String entryId) {
        String roundId = null;
        Cursor c = query("SELECT round_id FROM event_entries WHERE id = ?", entryId);
        try {
            if (c.moveToFirst()) {
                roundId = c.getString(0);
            }
        } finally {
            c.close();
        }
        if (roundId == null) {
            throw new ValidationException("This entrant is no longer in the event.");
        }
        return requireRound(roundId);
    }

    @Override
    public String addEntry(User actorArg, String roundId, String registrationId, int lane) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "add entrants to an event");
        EventRound round = requireRound(roundId);
        if (round.isFinal) {
            throw new ValidationException("These results are official. Reopen them first.");
        }
        Registration entrant = rawRegistration(registrationId);
        if (entrant == null || !round.categoryId.equals(entrant.categoryId)) {
            throw new ValidationException("This entrant is not registered in this category.");
        }
        if (!entrant.isApproved()) {
            throw new ValidationException(entrant.name + " is not approved yet.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (count(db, "event_entries", "round_id = ? AND registration_id = ?", roundId,
                registrationId) > 0) {
            throw new ValidationException(entrant.name + " is already in this event.");
        }

        List<EventEntry> entries = getEntries(roundId);
        if (round.isMeasured()) {
            // Throwing order: next free number unless one was given.
            if (lane <= 0) {
                lane = entries.size() + 1;
            }
        } else {
            int lanes = Math.max(1, round.rules().getInt("lanes", 8));
            if (entries.size() >= lanes) {
                throw new ValidationException("This race is full: there are " + lanes
                        + " lanes.");
            }
            if (lane < 1 || lane > lanes) {
                throw new ValidationException("Choose a lane from 1 to " + lanes + ".");
            }
        }
        for (EventEntry e : entries) {
            if (e.lane == lane) {
                throw new ValidationException((round.isMeasured() ? "Position " : "Lane ")
                        + lane + " is already taken by " + e.name + ".");
            }
        }

        String id = newId();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("round_id", roundId);
        cv.put("registration_id", registrationId);
        cv.put("lane", lane);
        db.insertOrThrow("event_entries", null, cv);
        return id;
    }

    @Override
    public void removeEntry(User actorArg, String entryId) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "remove entrants from an event");
        EventRound round = roundOfEntry(entryId);
        if (round.isFinal) {
            throw new ValidationException("These results are official. Reopen them first.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("event_entries", "id = ?", new String[]{entryId});
            storeRanking(db, round);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ----- Status and results -----

    /** Common checks before a result is written, the same as for a match. */
    private void requireRoundScoring(User actor, EventRound round) {
        AccessControl.require(AccessControl.canScore(actor, round.assignedTo, round.isFinal),
                actor, "enter results for this event");
        if (round.isFinal) {
            throw new ValidationException("These results are official. They have to be "
                    + "reopened with a reason before they can change.");
        }
        if (!Match.STATUS_LIVE.equals(round.status)
                && !Match.STATUS_COMPLETED.equals(round.status)) {
            throw new ValidationException("Results can only be entered while the event is "
                    + "Live. Its status is " + round.status + ": start or resume it first.");
        }
    }

    @Override
    public void setRoundStatus(User actorArg, String roundId, int expectedVersion,
                               String status) {
        User actor = trusted(actorArg);
        EventRound round = requireRound(roundId);
        AccessControl.require(AccessControl.canScore(actor, round.assignedTo, round.isFinal),
                actor, "change the status of this event");
        if (round.isFinal) {
            throw new ValidationException("These results are official, so the status cannot "
                    + "change.");
        }
        if (!Match.isValidStatus(status)) {
            throw new ValidationException("Choose a valid status.");
        }
        if (Match.STATUS_COMPLETED.equals(status)) {
            // Every entrant needs a result or a reason for not having one.
            int missing = 0;
            List<EventEntry> entries = getEntries(roundId);
            for (EventEntry e : entries) {
                boolean hasResult = round.isMeasured() ? !e.attempts.isEmpty() : e.timeMs != null;
                if (!hasResult && e.hasNormalStatus()) {
                    missing++;
                }
            }
            if (entries.isEmpty()) {
                throw new ValidationException("This event has no entrants.");
            }
            if (missing > 0) {
                throw new ValidationException(missing + " entrant(s) have no result yet. "
                        + "Enter a result, or mark them as did not start, did not finish or "
                        + "disqualified.");
            }
        }
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        writeRound(round, expectedVersion, cv, actor, ScoreAudit.ACTION_STATUS,
                "Status: " + status, "");
    }

    @Override
    public void saveTimedResult(User actorArg, String entryId, int expectedVersion,
                                Integer timeMs, String resultStatus) {
        User actor = trusted(actorArg);
        EventRound round = roundOfEntry(entryId);
        requireRoundScoring(actor, round);
        if (round.isMeasured()) {
            throw new ValidationException("This event is measured, not timed.");
        }
        String status = checkResultStatus(resultStatus);
        if (timeMs != null && timeMs <= 0) {
            throw new ValidationException("Enter the time, for example 12.34 or 1:02.35.");
        }
        // Someone who did not start or finish has no time.
        if (EventEntry.STATUS_DNS.equals(status) || EventEntry.STATUS_DNF.equals(status)) {
            timeMs = null;
        }
        EventEntry entry = findEntry(getEntries(round.id), entryId);

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            if (timeMs == null) {
                cv.putNull("time_ms");
            } else {
                cv.put("time_ms", timeMs);
            }
            cv.put("result_status", status);
            db.update("event_entries", cv, "id = ?", new String[]{entryId});
            storeRanking(db, round);
            writeRound(round, expectedVersion, new ContentValues(), actor,
                    ScoreAudit.ACTION_SCORE_UPDATE,
                    "Lane " + entry.lane + " " + entry.name + ": "
                            + (timeMs == null ? "no time" : Measure.formatTime(timeMs))
                            + (status.isEmpty() ? "" : " " + status), "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void saveAttempt(User actorArg, String entryId, int expectedVersion, int number,
                            Integer distanceCm, boolean foul) {
        User actor = trusted(actorArg);
        EventRound round = roundOfEntry(entryId);
        requireRoundScoring(actor, round);
        if (!round.isMeasured()) {
            throw new ValidationException("This event is timed, not measured.");
        }
        RuleSet rules = round.rules();
        int attempts = Math.max(1, rules.getInt("attempts", 3));
        int finalistCount = Math.max(0, rules.getInt("finalists", 0));
        int extra = finalistCount > 0 ? Math.max(0, rules.getInt("extra_attempts", 0)) : 0;
        boolean secondBest = rules.get("tie_break", "").startsWith("Second-best");

        List<EventEntry> entries = getEntries(round.id);
        EventEntry entry = findEntry(entries, entryId);
        if (number < 1 || number > attempts + extra) {
            throw new ValidationException("This event allows attempts 1 to "
                    + (attempts + extra) + ".");
        }
        // Attempts are taken in order; an existing one may be corrected.
        int taken = entry.attempts.size();
        if (number > taken + 1) {
            throw new ValidationException("Enter attempt " + (taken + 1) + " first.");
        }
        if (number > attempts
                && !EventRanking.finalists(entries, attempts, finalistCount, secondBest)
                .contains(entry)) {
            throw new ValidationException("Only the best " + finalistCount + " after "
                    + attempts + " attempts take the extra attempts.");
        }
        if (foul) {
            distanceCm = null;
        } else if (distanceCm == null || distanceCm <= 0) {
            throw new ValidationException("Enter the measured distance, for example 12.45, "
                    + "or mark the attempt as a foul.");
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            if (distanceCm == null) {
                cv.putNull("distance_cm");
            } else {
                cv.put("distance_cm", distanceCm);
            }
            cv.put("foul", foul ? 1 : 0);
            if (db.update("event_attempts", cv, "entry_id = ? AND number = ?",
                    new String[]{entryId, String.valueOf(number)}) == 0) {
                cv.put("id", newId());
                cv.put("entry_id", entryId);
                cv.put("number", number);
                db.insertOrThrow("event_attempts", null, cv);
            }
            storeRanking(db, round);
            writeRound(round, expectedVersion, new ContentValues(), actor,
                    ScoreAudit.ACTION_SCORE_UPDATE,
                    entry.name + ", attempt " + number + ": "
                            + (foul ? "foul" : Measure.formatDistance(distanceCm) + " m"), "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void setEntryStatus(User actorArg, String entryId, int expectedVersion,
                               String resultStatus) {
        User actor = trusted(actorArg);
        EventRound round = roundOfEntry(entryId);
        requireRoundScoring(actor, round);
        String status = checkResultStatus(resultStatus);
        EventEntry entry = findEntry(getEntries(round.id), entryId);

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("result_status", status);
            db.update("event_entries", cv, "id = ?", new String[]{entryId});
            storeRanking(db, round);
            writeRound(round, expectedVersion, new ContentValues(), actor,
                    ScoreAudit.ACTION_SCORE_UPDATE,
                    entry.name + ": " + (status.isEmpty() ? "result counts" : status), "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static String checkResultStatus(String status) {
        String value = status == null ? "" : status.trim();
        for (String known : EventEntry.STATUSES) {
            if (known.equals(value)) {
                return value;
            }
        }
        throw new ValidationException("Choose a valid result status.");
    }

    /**
     * Works out positions (and qualifiers, for a heat) from the stored results and saves
     * them on every entrant. Called inside the transaction of each result change, so the
     * positions always agree with the results.
     */
    private void storeRanking(SQLiteDatabase db, EventRound round) {
        List<EventEntry> entries = getEntries(round.id);
        RuleSet rules = round.rules();
        if (round.isMeasured()) {
            EventRanking.rankMeasured(entries,
                    rules.get("tie_break", "").startsWith("Second-best"));
        } else {
            boolean heatsUsed = round.isHeat() && rules.isYes("heats");
            EventRanking.rankTimed(entries,
                    heatsUsed ? Math.max(0, rules.getInt("qualify_per_heat", 0)) : 0);
        }
        for (EventEntry e : entries) {
            ContentValues cv = new ContentValues();
            if (e.position == null) {
                cv.putNull("position");
            } else {
                cv.put("position", e.position);
            }
            cv.put("qualified", e.qualified ? 1 : 0);
            if (e.bestCm == null) {
                cv.putNull("best_cm");
            } else {
                cv.put("best_cm", e.bestCm);
            }
            db.update("event_entries", cv, "id = ?", new String[]{e.id});
        }
    }

    /** Saves a change to a heat or final and records it in its history, like writeMatch. */
    private void writeRound(EventRound before, int expectedVersion, ContentValues cv, User actor,
                            String action, String detail, String reason) {
        long now = System.currentTimeMillis();
        cv.put("version", expectedVersion + 1);
        cv.put("updated_at", now);
        cv.put("updated_by", actor.id);

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (db.update("event_rounds", cv, "id = ? AND version = ?",
                    new String[]{before.id, String.valueOf(expectedVersion)}) == 0) {
                throw new ConflictException("Someone else updated this event a moment ago. "
                        + "Your change was not saved. Check the latest results and try again.");
            }
            ContentValues audit = new ContentValues();
            audit.put("id", newId());
            audit.put("round_id", before.id);
            audit.put("changed_by", actor.id);
            audit.put("changed_at", now);
            audit.put("action", action);
            audit.put("detail", detail);
            audit.put("reason", reason);
            db.insertOrThrow("event_audit", null, audit);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void finaliseRound(User actorArg, String roundId, int expectedVersion) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "make results official");
        EventRound round = requireRound(roundId);
        if (round.isFinal) {
            throw new ValidationException("These results are already official.");
        }
        if (!Match.STATUS_COMPLETED.equals(round.status)) {
            throw new ValidationException("Only a completed event can be made official.");
        }
        ContentValues cv = new ContentValues();
        cv.put("is_final", 1);
        writeRound(round, expectedVersion, cv, actor, ScoreAudit.ACTION_FINALISED,
                "Results made official", "");
    }

    @Override
    public void reopenRound(User actorArg, String roundId, int expectedVersion, String reason) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "reopen official results");
        EventRound round = requireRound(roundId);
        if (!round.isFinal) {
            throw new ValidationException("These results are not official, so they do not "
                    + "need reopening.");
        }
        if (clean(reason).isEmpty()) {
            throw new ValidationException("Give a reason for reopening official results.");
        }
        ContentValues cv = new ContentValues();
        cv.put("is_final", 0);
        writeRound(round, expectedVersion, cv, actor, ScoreAudit.ACTION_REOPENED,
                "Results reopened", clean(reason));
    }

    @Override
    public List<ScoreAudit> getRoundAudit(User actorArg, String roundId) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canViewScoreHistory(actor), actor,
                "view the result history");
        List<ScoreAudit> list = new ArrayList<>();
        Cursor c = query("SELECT a.*, u.name AS changed_by_name FROM event_audit a "
                + "LEFT JOIN users u ON u.id = a.changed_by "
                + "WHERE a.round_id = ? ORDER BY a.changed_at DESC, a.rowid DESC", roundId);
        try {
            while (c.moveToNext()) {
                ScoreAudit a = new ScoreAudit();
                a.id = text(c, "id");
                a.matchId = text(c, "round_id");
                a.changedByName = text(c, "changed_by_name");
                a.changedAt = c.getLong(c.getColumnIndexOrThrow("changed_at"));
                a.action = text(c, "action");
                a.oldScore = "";
                a.newScore = text(c, "detail");
                a.reason = text(c, "reason");
                list.add(a);
            }
        } finally {
            c.close();
        }
        return list;
    }

    /**
     * Builds the final of a race from its heats: everyone who qualified by position,
     * plus the fastest of the rest as far as the rules allow. All heats must be official.
     */
    @Override
    public String createFinalFromHeats(User actorArg, String categoryId) {
        User actor = trusted(actorArg);
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "create the final");
        Category category = getCategory(categoryId);
        if (category == null || !SportTemplate.ENGINE_TIMED.equals(category.engine)
                || !category.ruleSet().isYes("heats")) {
            throw new ValidationException("Only a race that is run in heats has a final "
                    + "made from them.");
        }
        List<EventRound> heats = new ArrayList<>();
        for (EventRound r : getRounds(categoryId)) {
            if (!r.isHeat()) {
                throw new ValidationException("This category already has a final.");
            }
            if (!r.isFinal) {
                throw new ValidationException(r.title() + " is not official yet. Every heat "
                        + "has to be made official before the final is drawn.");
            }
            heats.add(r);
        }
        if (heats.isEmpty()) {
            throw new ValidationException("There are no heats yet.");
        }

        List<EventEntry> all = new ArrayList<>();
        for (EventRound heat : heats) {
            all.addAll(getEntries(heat.id));
        }
        List<EventEntry> finalists = new ArrayList<>();
        for (EventEntry e : all) {
            if (e.qualified) {
                finalists.add(e);
            }
        }
        RuleSet rules = category.ruleSet();
        finalists.addAll(EventRanking.fastestNonQualifiers(all,
                Math.max(0, rules.getInt("fastest_losers", 0))));
        int lanes = Math.max(1, rules.getInt("lanes", 8));
        if (finalists.isEmpty()) {
            throw new ValidationException("Nobody qualified from the heats.");
        }
        if (finalists.size() > lanes) {
            throw new ValidationException(finalists.size() + " entrants qualified but there "
                    + "are only " + lanes + " lanes. Change the qualifying rules.");
        }
        // Lanes in order of heat time, fastest in lane 1.
        java.util.Collections.sort(finalists, new java.util.Comparator<EventEntry>() {
            @Override
            public int compare(EventEntry x, EventEntry y) {
                return x.timeMs - y.timeMs;
            }
        });

        EventRound finalRound = new EventRound();
        finalRound.categoryId = categoryId;
        finalRound.stage = EventRound.STAGE_FINAL;
        finalRound.date = DateUtil.today().compareTo(heats.get(0).date) > 0
                ? DateUtil.today() : heats.get(0).date;
        finalRound.time = "";
        finalRound.venue = heats.get(0).venue;
        finalRound.assignedTo = heats.get(0).assignedTo;

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            String id = saveRound(actor, finalRound);
            int lane = 1;
            for (EventEntry e : finalists) {
                ContentValues cv = new ContentValues();
                cv.put("id", newId());
                cv.put("round_id", id);
                cv.put("registration_id", e.registrationId);
                cv.put("lane", lane++);
                db.insertOrThrow("event_entries", null, cv);
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
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
     * never trusted for its role: only its id is used to look the real record up.
     */
    private User trusted(User actor) {
        if (actor == null || actor.id == null) {
            return null;
        }
        return getUser(actor.id);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private Cursor query(String sql, String... args) {
        return helper.getReadableDatabase().rawQuery(sql, args);
    }

    private static int count(SQLiteDatabase db, String table, String where, String... args) {
        return (int) DatabaseUtils.queryNumEntries(db, table, where, args);
    }

    /** Text column, with NULL returned as an empty string. */
    private static String text(Cursor c, String column) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? "" : c.getString(index);
    }

    /** Text column that may really be NULL (used for optional ids). */
    private static String textOrNull(Cursor c, String column) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? null : c.getString(index);
    }

    private static int number(Cursor c, String column) {
        return c.getInt(c.getColumnIndexOrThrow(column));
    }

    private static Integer numberOrNull(Cursor c, String column) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? null : c.getInt(index);
    }

    private static User readUser(Cursor c) {
        User u = new User();
        u.id = text(c, "id");
        u.name = text(c, "name");
        u.email = text(c, "email");
        u.role = text(c, "role");
        u.active = number(c, "active") == 1;
        u.branchId = textOrNull(c, "branch_id");
        u.branchName = text(c, "branch_name");
        return u;
    }

    private static List<User> readUsers(Cursor c) {
        List<User> list = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                list.add(readUser(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    private static Sport readSport(Cursor c) {
        Sport s = new Sport();
        s.id = text(c, "id");
        s.name = text(c, "name");
        s.description = text(c, "description");
        s.templateKey = text(c, "template_key");
        return s;
    }

    private static Category readCategory(Cursor c) {
        Category k = new Category();
        k.id = text(c, "id");
        k.sportId = text(c, "sport_id");
        k.name = text(c, "name");
        k.type = text(c, "type");
        k.minSize = number(c, "min_size");
        k.maxSize = number(c, "max_size");
        k.deadline = text(c, "deadline");
        k.format = text(c, "format");
        k.ptsWin = number(c, "pts_win");
        k.ptsDraw = number(c, "pts_draw");
        k.ptsLoss = number(c, "pts_loss");
        k.engine = text(c, "engine");
        k.rules = text(c, "rules");
        return k;
    }

    /** Reads a row of REG_SELECT. What the caller may see is decided by forViewer. */
    private static Registration readRegistration(Cursor c) {
        Registration r = new Registration();
        r.id = text(c, "id");
        r.categoryId = text(c, "category_id");
        r.name = text(c, "name");
        r.branchId = textOrNull(c, "branch_id");
        r.branchName = text(c, "branch_name");
        r.branchCode = text(c, "branch_code");
        r.year = number(c, "year");
        r.teamLabel = text(c, "team_label");
        r.logoPath = text(c, "logo_path");
        r.contactName = text(c, "contact_name");
        r.contactPhone = text(c, "contact_phone");
        r.members = text(c, "members");
        r.status = text(c, "status");
        r.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        r.categoryName = text(c, "category_name");
        r.sportName = text(c, "sport_name");
        return r;
    }

    private static Match readMatch(Cursor c) {
        Match m = new Match();
        m.id = text(c, "id");
        m.categoryId = text(c, "category_id");
        m.teamAId = text(c, "team_a_id");
        m.teamBId = text(c, "team_b_id");
        m.date = text(c, "match_date");
        m.time = text(c, "match_time");
        m.venue = text(c, "venue");
        m.round = text(c, "round");
        m.status = text(c, "status");
        m.scoreA = numberOrNull(c, "score_a");
        m.scoreB = numberOrNull(c, "score_b");
        m.scoreNote = text(c, "score_note");
        m.isFinal = number(c, "is_final") == 1;
        m.assignedTo = textOrNull(c, "assigned_to");
        m.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        m.updatedBy = textOrNull(c, "updated_by");
        m.version = number(c, "version");
        m.teamAName = text(c, "team_a_name");
        m.teamBName = text(c, "team_b_name");
        m.categoryName = text(c, "category_name");
        m.categoryFormat = text(c, "category_format");
        m.sportName = text(c, "sport_name");
        m.assignedName = text(c, "assigned_name");
        m.updatedByName = text(c, "updated_by_name");
        m.categoryType = text(c, "category_type");
        m.categoryEngine = text(c, "category_engine");
        m.categoryRules = text(c, "category_rules");
        m.externalUrl = text(c, "external_url");
        m.currentPeriod = number(c, "current_period");
        m.penA = numberOrNull(c, "pen_a");
        m.penB = numberOrNull(c, "pen_b");
        m.branchAName = text(c, "branch_a_name");
        m.branchACode = text(c, "branch_a_code");
        m.yearA = number(c, "year_a");
        m.logoA = text(c, "logo_a");
        m.branchBName = text(c, "branch_b_name");
        m.branchBCode = text(c, "branch_b_code");
        m.yearB = number(c, "year_b");
        m.logoB = text(c, "logo_b");
        return m;
    }
}
