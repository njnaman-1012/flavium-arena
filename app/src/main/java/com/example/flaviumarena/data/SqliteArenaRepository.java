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
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.StandingRow;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.scoring.Medals;
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
    final SessionStore sessions;
    private final MatchStore matches;
    private final EventStore events;
    private final AccountStore accounts;
    private final CategoryStore categories;
    private final RegistrationStore registrations;

    // Why a login session ended (see SessionStore and auth/Session).
    public static final String END_TAKEN_OVER = "TAKEN_OVER";
    public static final String END_EXPIRED = "EXPIRED";
    public static final String END_LOGGED_OUT = "LOGGED_OUT";
    public static final String END_DEACTIVATED = "ACCOUNT_DEACTIVATED";
    public static final String END_PASSWORD_RESET = "PASSWORD_RESET";

    public SqliteArenaRepository(DbHelper helper) {
        this.helper = helper;
        this.sessions = new SessionStore(helper);
        this.matches = new MatchStore(this, helper);
        this.events = new EventStore(this, helper);
        this.accounts = new AccountStore(this, helper);
        this.categories = new CategoryStore(this, helper);
        this.registrations = new RegistrationStore(this, helper);
    }

    // =====================================================================
    // Accounts and login: see AccountStore
    // =====================================================================

    @Override
    public User login(String email, String password, String expectedRole) {
        return accounts.login(email, password, expectedRole);
    }

    @Override
    public User login(String email, String password, String expectedRole, String device,
            boolean takeOver) {
        return accounts.login(email, password, expectedRole, device, takeOver);
    }

    @Override
    public User resumeSession(String sessionId) {
        return accounts.resumeSession(sessionId);
    }

    @Override
    public String getSessionEndReason(String sessionId) {
        return accounts.getSessionEndReason(sessionId);
    }

    @Override
    public void logout(User actor) {
        accounts.logout(actor);
    }

    @Override
    public void changeOwnPassword(User actorArg, String currentPassword, String newPassword) {
        accounts.changeOwnPassword(actorArg, currentPassword, newPassword);
    }

    @Override
    public User getUser(String id) {
        return accounts.getUser(id);
    }

    @Override
    public boolean hasFaculty() {
        return accounts.hasFaculty();
    }

    @Override
    public User createFirstFaculty(String name, String email, String password,
            String authorisedEmail) {
        return accounts.createFirstFaculty(name, email, password, authorisedEmail);
    }

    @Override
    public List<User> getUsers(User actorArg) {
        return accounts.getUsers(actorArg);
    }

    @Override
    public List<User> getAsooCores(User actorArg) {
        return accounts.getAsooCores(actorArg);
    }

    @Override
    public String createUser(User actorArg, String name, String email, String password,
            String role, String branchId) {
        return accounts.createUser(actorArg, name, email, password, role, branchId);
    }

    @Override
    public void updateUser(User actorArg, String userId, String name, String email,
            String role, String branchId) {
        accounts.updateUser(actorArg, userId, name, email, role, branchId);
    }

    @Override
    public void resetPassword(User actorArg, String userId, String newPassword) {
        accounts.resetPassword(actorArg, userId, newPassword);
    }

    @Override
    public void setUserActive(User actorArg, String userId, boolean active) {
        accounts.setUserActive(actorArg, userId, active);
    }

    @Override
    public void deleteUser(User actorArg, String userId) {
        accounts.deleteUser(actorArg, userId);
    }

    @Override
    public void setCoachSports(User actorArg, String userId, List<String> sportIds) {
        accounts.setCoachSports(actorArg, userId, sportIds);
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
    void audit(User actor, String action, String detail) {
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
    SportTemplate templateOf(Sport sport) {
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
    // Categories: see CategoryStore
    // =====================================================================

    @Override
    public List<Category> getCategories(String sportId) {
        return categories.getCategories(sportId);
    }

    @Override
    public Category getCategory(String id) {
        return categories.getCategory(id);
    }

    @Override
    public String saveCategory(User actorArg, Category category) {
        return categories.saveCategory(actorArg, category);
    }

    @Override
    public void deleteCategory(User actorArg, String id) {
        categories.deleteCategory(actorArg, id);
    }

    // =====================================================================
    // Registrations: see RegistrationStore
    // =====================================================================

    @Override
    public List<Registration> getRegistrations(User actorArg, String categoryId) {
        return registrations.getRegistrations(actorArg, categoryId);
    }

    @Override
    public List<Registration> getRegistrationsOfBranch(User actorArg, String branchId) {
        return registrations.getRegistrationsOfBranch(actorArg, branchId);
    }

    @Override
    public Registration getRegistration(User actorArg, String id) {
        return registrations.getRegistration(actorArg, id);
    }

    @Override
    public String saveRegistration(User actorArg, Registration registration) {
        return registrations.saveRegistration(actorArg, registration);
    }

    @Override
    public void setRegistrationLogo(User actorArg, String id, String logoPath) {
        registrations.setRegistrationLogo(actorArg, id, logoPath);
    }

    @Override
    public void setRegistrationStatus(User actorArg, String id, String status) {
        registrations.setRegistrationStatus(actorArg, id, status);
    }

    @Override
    public void deleteRegistration(User actorArg, String id) {
        registrations.deleteRegistration(actorArg, id);
    }

    /** The registration exactly as stored. Only for checks inside the data layer. */
    Registration rawRegistration(String id) {
        return registrations.rawRegistration(id);
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

    /** The trusted user as they count inside one sport (see AccessControl.inSport). */
    User actorIn(User actorArg, String sportId) {
        return AccessControl.inSport(trusted(actorArg), sportId);
    }

    String lookup(String sql, String argument) {
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

    String sportOfRegistration(String registrationId) {
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

}
