package in.flavium.arena.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import in.flavium.arena.model.Category;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.Role;
import in.flavium.arena.util.DateUtil;
import in.flavium.arena.util.PasswordUtil;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * DEVELOPMENT ONLY. This file lives in app/src/debug, so it is compiled into debug
 * builds and is not part of a release build at all (the release build gets the empty
 * class of the same name in app/src/release).
 *
 * It provides three development accounts and some sample branches, sports, teams and
 * fixtures. The accounts can also be created or repaired later, see createOrRepair.
 * There is deliberately no development Faculty account: Faculty is always created
 * through the first-Faculty set-up screen.
 *
 * No password is written here. Each development password is made at random when the
 * app (or a test run) starts, so it is valid only inside this one process. The login
 * helper of a debug build therefore resets the account to the current password before
 * it fills in the form (see DevTools in app/src/debug).
 */
public final class DevData {

    /** Declared first: the passwords below are made with it while the class loads. */
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String CORE_EMAIL = "core@flavium.test";
    public static final String CORE_PASSWORD = newPassword();
    public static final String ASOO_EMAIL = "asoo@flavium.test";
    public static final String ASOO_PASSWORD = newPassword();
    public static final String CAPTAIN_EMAIL = "captain@flavium.test";
    public static final String CAPTAIN_PASSWORD = newPassword();

    /** Branch of the development captain account. */
    public static final String CAPTAIN_BRANCH = "Computer Engineering";

    /** The development branches: names and short codes. */
    private static final String[][] BRANCHES = {
            {"Computer Engineering", "CE"},
            {"Information Technology", "IT"},
            {"Mechanical Engineering", "ME"},
            {"Electronics and Telecommunication", "EXTC"},
            {"Civil Engineering", "CIVIL"}};

    private DevData() {
    }

    /**
     * A new random password that meets the app's rules (letters and digits, long
     * enough). For the development accounts, the self-test and the automated tests, so
     * that no reusable password is kept in the source code.
     */
    public static String newPassword() {
        String letters = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder password = new StringBuilder("Dev-");
        for (int i = 0; i < 12; i++) {
            password.append(letters.charAt(RANDOM.nextInt(letters.length())));
        }
        return password.append(1000 + RANDOM.nextInt(9000)).toString();
    }

    /** Email and password of the development account for a role, or null if there is none. */
    public static String[] account(String role) {
        if (Role.CORE.equals(role)) {
            return new String[]{CORE_EMAIL, CORE_PASSWORD};
        }
        if (Role.ASOO_CORE.equals(role)) {
            return new String[]{ASOO_EMAIL, ASOO_PASSWORD};
        }
        if (Role.CAPTAIN.equals(role)) {
            return new String[]{CAPTAIN_EMAIL, CAPTAIN_PASSWORD};
        }
        return null;
    }

    private static String nameOf(String role) {
        return Role.CORE.equals(role) ? "Test Core"
                : Role.ASOO_CORE.equals(role) ? "Test Asoo Core" : "Test Captain";
    }

    /**
     * Creates the development account of a role, or puts an existing one back in working
     * order: active, right role, the current password, not locked. Nothing else in the
     * database is touched. Returns a sentence that says what was done.
     *
     * Why this is needed: the passwords change each time the app starts, and a database
     * that was created by an older version of the app may not have the account at all.
     *
     * This runs only when the button on the login screen of a debug build is pressed.
     */
    public static String createOrRepair(Context context, String role) {
        String[] account = account(role);
        if (account == null) {
            throw new ValidationException("There is no development account for this role.");
        }
        SQLiteDatabase db = RepositoryProvider.helper(context).getWritableDatabase();
        db.beginTransaction();
        try {
            String id = null;
            String branchId = null;
            Cursor c = db.rawQuery("SELECT id, branch_id FROM users WHERE email = ?",
                    new String[]{account[0]});
            try {
                if (c.moveToFirst()) {
                    id = c.getString(0);
                    branchId = c.isNull(1) ? null : c.getString(1);
                }
            } finally {
                c.close();
            }
            boolean created = id == null;
            if (created) {
                id = newId();
            }
            if (Role.CAPTAIN.equals(role)) {
                branchId = branchFor(db, id, branchId);
            } else {
                branchId = null;
            }

            String salt = PasswordUtil.newSalt();
            ContentValues cv = new ContentValues();
            cv.put("salt", salt);
            cv.put("password_hash", PasswordUtil.hash(account[1], salt));
            cv.put("role", role);
            cv.put("active", 1);
            cv.put("failed_logins", 0);
            cv.put("locked_until", 0);
            if (branchId == null) {
                cv.putNull("branch_id");
            } else {
                cv.put("branch_id", branchId);
            }
            if (created) {
                cv.put("id", id);
                cv.put("name", nameOf(role));
                cv.put("email", account[0]);
                db.insertOrThrow("users", null, cv);
            } else {
                db.update("users", cv, "id = ?", new String[]{id});
            }
            String what = (created ? "Created" : "Repaired") + " the development "
                    + Role.label(role) + " account " + account[0];
            if (branchId != null) {
                what += " for " + DatabaseUtils.stringForQuery(db,
                        "SELECT name FROM branches WHERE id = ?", new String[]{branchId});
            }
            DbHelper.writeAdminAudit(db, null, "Development build", "DEV_ACCOUNT", what + ".");
            db.setTransactionSuccessful();
            return what + ".";
        } finally {
            db.endTransaction();
        }
    }

    /**
     * The branch of the development Branch CR. A branch has one active Branch CR, so the
     * account keeps its branch when that is still valid, and otherwise gets an active
     * branch that has none (the usual development branch if it is free).
     */
    private static String branchFor(SQLiteDatabase db, String userId, String current) {
        String free = "b.active = 1 AND NOT EXISTS (SELECT 1 FROM users u "
                + "WHERE u.branch_id = b.id AND u.role = 'CAPTAIN' AND u.active = 1 "
                + "AND u.id <> ?)";
        if (current != null && DatabaseUtils.queryNumEntries(db, "branches b",
                "b.id = ? AND " + free, new String[]{current, userId}) > 0) {
            return current;
        }
        Cursor c = db.rawQuery("SELECT b.id FROM branches b WHERE " + free
                + " ORDER BY b.name = ? DESC, b.name LIMIT 1", new String[]{userId, CAPTAIN_BRANCH});
        try {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        } finally {
            c.close();
        }
        throw new ValidationException("Every active branch already has a Branch CR, so the "
                + "development account has no branch to manage. Add a branch or deactivate "
                + "one Branch CR first.");
    }

    public static void insert(SQLiteDatabase db) {
        // ----- Development branches (a release database starts with none) -----
        for (String[] branch : BRANCHES) {
            ContentValues cv = new ContentValues();
            cv.put("id", newId());
            cv.put("name", branch[0]);
            cv.put("code", branch[1]);
            cv.put("active", 1);
            db.insertOrThrow("branches", null, cv);
        }

        // ----- Development accounts -----
        String core = user(db, "Test Core", CORE_EMAIL, CORE_PASSWORD, Role.CORE, null);
        String asoo = user(db, "Test Asoo Core", ASOO_EMAIL, ASOO_PASSWORD, Role.ASOO_CORE, null);
        String captainBranch = DatabaseUtils.stringForQuery(db,
                "SELECT id FROM branches WHERE name = ?", new String[]{CAPTAIN_BRANCH});
        user(db, "Test Captain", CAPTAIN_EMAIL, CAPTAIN_PASSWORD, Role.CAPTAIN, captainBranch);

        String deadline = DateUtil.daysFromToday(30);
        String yesterday = DateUtil.daysFromToday(-1);
        String today = DateUtil.today();
        String tomorrow = DateUtil.daysFromToday(1);

        // ----- Football: a team league -----
        String football = sport(db, "Football", "Outdoor team sport");
        String sevens = category(db, football, "Men's 7-a-side", Category.TYPE_TEAM, 7, 10,
                deadline, Category.FORMAT_LEAGUE, 3, 1, 0);
        // Teams are identified by branch and academic year, not by a nickname.
        String ce = team(db, sevens, "Computer Engineering", 3, "Arjun Mehta", "9000000001", 7,
                Registration.STATUS_APPROVED);
        String it = team(db, sevens, "Information Technology", 3, "Rohan Das", "9000000002", 7,
                Registration.STATUS_APPROVED);
        String me = team(db, sevens, "Mechanical Engineering", 3, "Kabir Singh", "9000000003",
                8, Registration.STATUS_APPROVED);
        String extc = team(db, sevens, "Electronics and Telecommunication", 3, "Dev Patel",
                "9000000004", 7, Registration.STATUS_APPROVED);
        team(db, sevens, "Civil Engineering", 2, "Neil Roy", "9000000005", 7,
                Registration.STATUS_PENDING);

        // One finished and finalised match, one live match, two upcoming ones.
        match(db, sevens, ce, it, yesterday, "16:00", "Main Ground", "League Round 1",
                Match.STATUS_COMPLETED, 2, 1, true, asoo, core);
        match(db, sevens, me, extc, today, "16:00", "Main Ground", "League Round 1",
                Match.STATUS_LIVE, 1, 0, false, asoo, asoo);
        match(db, sevens, ce, me, tomorrow, "16:00", "Main Ground", "League Round 2",
                Match.STATUS_UPCOMING, null, null, false, asoo, core);
        match(db, sevens, it, extc, tomorrow, "17:30", "Main Ground", "League Round 2",
                Match.STATUS_UPCOMING, null, null, false, null, core);

        // ----- Badminton: an individual knockout -----
        String badminton = sport(db, "Badminton", "Indoor racquet sport");
        String singles = category(db, badminton, "Women's Singles", Category.TYPE_INDIVIDUAL,
                1, 1, deadline, Category.FORMAT_KNOCKOUT, 1, 0, 0);
        // Individuals play under their own name and represent a branch.
        String p1 = player(db, singles, "Ananya Rao", "Computer Engineering", 2, "9000000011",
                Registration.STATUS_APPROVED);
        String p2 = player(db, singles, "Meera Nair", "Information Technology", 2, "9000000012",
                Registration.STATUS_APPROVED);
        String p3 = player(db, singles, "Sara Khan", "Mechanical Engineering", 1, "9000000013",
                Registration.STATUS_APPROVED);
        String p4 = player(db, singles, "Tanvi Joshi", "Civil Engineering", 4, "9000000014",
                Registration.STATUS_APPROVED);
        player(db, singles, "Isha Verma", "Computer Engineering", 1, "9000000015",
                Registration.STATUS_PENDING);

        // A semi-final in progress: first game won 21-18, second game at 9-11.
        String semi = match(db, singles, p1, p2, today, "10:00", "Indoor Court 1", "Semi-final",
                Match.STATUS_LIVE, 1, 0, false, asoo, asoo);
        period(db, semi, 1, 21, 18);
        period(db, semi, 2, 9, 11);
        match(db, singles, p3, p4, today, "11:00", "Indoor Court 1", "Semi-final",
                Match.STATUS_UPCOMING, null, null, false, null, core);

        // ----- Sports with no fixtures yet -----
        // ----- Cricket: one fixture waiting for its CricHeroes link -----
        String cricket = sport(db, "Cricket", "Bat and ball team sport");
        String t10 = category(db, cricket, "Men's T10", Category.TYPE_TEAM, 11, 11,
                deadline, Category.FORMAT_LEAGUE, 2, 1, 0);
        String cricketCe = team(db, t10, "Computer Engineering", 3, "Aman Gupta", "9000000021",
                11, Registration.STATUS_APPROVED);
        String cricketMe = team(db, t10, "Mechanical Engineering", 3, "Ravi Kumar",
                "9000000022", 11, Registration.STATUS_APPROVED);
        match(db, t10, cricketCe, cricketMe, tomorrow, "09:00", "Cricket Ground",
                "League Round 1", Match.STATUS_UPCOMING, null, null, false, asoo, core);
        sport(db, "Chess", "Board game");
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String user(SQLiteDatabase db, String name, String email, String password,
                               String role, String branchId) {
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

    private static String sport(SQLiteDatabase db, String name, String description) {
        String id = newId();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name);
        cv.put("description", description);
        db.insertOrThrow("sports", null, cv);
        return id;
    }

    private static String category(SQLiteDatabase db, String sportId, String name, String type,
                                   int minSize, int maxSize, String deadline, String format,
                                   int win, int draw, int loss) {
        String id = newId();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("sport_id", sportId);
        cv.put("name", name);
        cv.put("type", type);
        cv.put("min_size", minSize);
        cv.put("max_size", maxSize);
        cv.put("deadline", deadline);
        cv.put("format", format);
        cv.put("pts_win", win);
        cv.put("pts_draw", draw);
        cv.put("pts_loss", loss);
        db.insertOrThrow("categories", null, cv);
        return id;
    }

    /** Adds the team of one branch and year, with placeholder member names. */
    private static String team(SQLiteDatabase db, String categoryId, String branchName, int year,
                               String captain, String phone, int size, String status) {
        StringBuilder members = new StringBuilder(captain);
        for (int i = 2; i <= size; i++) {
            members.append("\n").append("Player ").append(i);
        }
        return registration(db, categoryId, Registration.teamName(branchName, year, ""),
                branchName, year, captain, phone, members.toString(), status);
    }

    private static String player(SQLiteDatabase db, String categoryId, String name,
                                 String branchName, int year, String phone, String status) {
        return registration(db, categoryId, name, branchName, year, name, phone, "", status);
    }

    private static String registration(SQLiteDatabase db, String categoryId, String name,
                                       String branchName, int year, String contact,
                                       String phone, String members, String status) {
        String id = newId();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("category_id", categoryId);
        cv.put("name", name);
        cv.put("branch_id", DatabaseUtils.stringForQuery(db,
                "SELECT id FROM branches WHERE name = ?", new String[]{branchName}));
        cv.put("year", year);
        cv.put("contact_name", contact);
        cv.put("contact_phone", phone);
        cv.put("members", members);
        cv.put("status", status);
        cv.put("created_at", System.currentTimeMillis());
        db.insertOrThrow("registrations", null, cv);
        return id;
    }

    /** One set, game or frame of a match. */
    private static void period(SQLiteDatabase db, String matchId, int number, int scoreA,
                               int scoreB) {
        ContentValues cv = new ContentValues();
        cv.put("id", newId());
        cv.put("match_id", matchId);
        cv.put("number", number);
        cv.put("score_a", scoreA);
        cv.put("score_b", scoreB);
        db.insertOrThrow("match_periods", null, cv);
    }

    private static String match(SQLiteDatabase db, String categoryId, String teamA, String teamB,
                              String date, String time, String venue, String round, String status,
                              Integer scoreA, Integer scoreB, boolean isFinal, String assignedTo,
                              String updatedBy) {
        String id = newId();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("category_id", categoryId);
        cv.put("team_a_id", teamA);
        cv.put("team_b_id", teamB);
        cv.put("match_date", date);
        cv.put("match_time", time);
        cv.put("venue", venue);
        cv.put("round", round);
        cv.put("status", status);
        if (scoreA != null && scoreB != null) {
            cv.put("score_a", scoreA);
            cv.put("score_b", scoreB);
        }
        cv.put("is_final", isFinal ? 1 : 0);
        if (assignedTo != null) {
            cv.put("assigned_to", assignedTo);
        }
        cv.put("updated_at", System.currentTimeMillis());
        cv.put("updated_by", updatedBy);
        cv.put("version", 1);
        db.insertOrThrow("matches", null, cv);
        return id;
    }
}
