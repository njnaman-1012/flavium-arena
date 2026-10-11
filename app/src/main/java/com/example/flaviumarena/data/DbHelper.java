package com.example.flaviumarena.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.example.flaviumarena.R;
import com.example.flaviumarena.model.AppSettings;

import java.util.UUID;

/**
 * Creates and upgrades the SQLite database. Every table uses a text id (a UUID) as its
 * primary key, so the same ids can be reused as document ids when the data moves online.
 *
 * Version history
 *   1  users (Core / Asoo Core / Viewer), sports, categories, registrations, matches,
 *      score_audit.
 *   2  Four roles (Faculty, Core, Asoo Core, Branch Captain), accounts can be deactivated,
 *      captains belong to a branch. New tables: branches, settings, admin_audit.
 *   3  A registration belongs to a branch and an academic year, and can have a team
 *      label and a logo. Accounts count wrong passwords so they can be locked.
 *   4  Sport templates are stored in the database. A sport remembers its template and
 *      a category keeps its own copy of the rules and its scoring engine.
 *   5  A match can be PAUSED, can carry a CricHeroes link, the period in play and a
 *      penalty shoot-out score. New tables: match_periods (sets, games, innings) and
 *      match_events (goals, cards...).
 *   6  Events with many entrants (races, relays, swimming, throws): event_rounds,
 *      event_entries, event_attempts and event_audit.
 */
public class DbHelper extends SQLiteOpenHelper {

    public static final String DB_NAME = "flavium_arena.db";
    public static final int DB_VERSION = 6;
    private static final String TAG = "DbHelper";

    private final boolean seedDevData;
    /** Needed to read the sport catalogue from the app's assets. */
    private final Context appContext;

    /**
     * The app's real database. Development accounts and sample data are added only when
     * BOTH conditions hold: the build's configuration asks for them, and the app is a
     * debuggable build. A release build therefore never contains the development
     * accounts, even if the configuration value were switched on by mistake.
     */
    public DbHelper(Context context) {
        this(context, DB_NAME, context.getResources().getBoolean(R.bool.seed_dev_data)
                && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
    }

    /** Passing null as the name creates a temporary in-memory database (used by tests). */
    public DbHelper(Context context, String name, boolean seedDevData) {
        super(context, name, null, DB_VERSION);
        this.seedDevData = seedDevData;
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Without this line SQLite ignores the REFERENCES rules below. It is switched on
        // here, after onCreate/onUpgrade, because an upgrade has to rebuild a table and
        // SQLite cannot do that while the rules are being enforced.
        if (!db.isReadOnly()) {
            db.setForeignKeyConstraintsEnabled(true);
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createBranches(db);
        createUsers(db, "users");

        db.execSQL("CREATE TABLE sports ("
                + "id TEXT PRIMARY KEY, "
                + "name TEXT NOT NULL UNIQUE COLLATE NOCASE, "
                + "description TEXT NOT NULL DEFAULT '')");

        db.execSQL("CREATE TABLE categories ("
                + "id TEXT PRIMARY KEY, "
                + "sport_id TEXT NOT NULL REFERENCES sports(id), "
                + "name TEXT NOT NULL COLLATE NOCASE, "
                + "type TEXT NOT NULL CHECK (type IN ('INDIVIDUAL','TEAM')), "
                + "min_size INTEGER NOT NULL, "
                + "max_size INTEGER NOT NULL, "
                + "deadline TEXT NOT NULL DEFAULT '', "
                + "format TEXT NOT NULL CHECK (format IN ('LEAGUE','KNOCKOUT')), "
                + "pts_win INTEGER NOT NULL, "
                + "pts_draw INTEGER NOT NULL, "
                + "pts_loss INTEGER NOT NULL, "
                + "UNIQUE (sport_id, name))");

        db.execSQL("CREATE TABLE registrations ("
                + "id TEXT PRIMARY KEY, "
                + "category_id TEXT NOT NULL REFERENCES categories(id), "
                + "name TEXT NOT NULL COLLATE NOCASE, "
                + "contact_name TEXT NOT NULL DEFAULT '', "
                + "contact_phone TEXT NOT NULL DEFAULT '', "
                + "members TEXT NOT NULL DEFAULT '', "
                + "status TEXT NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')), "
                + "created_at INTEGER NOT NULL, "
                + "UNIQUE (category_id, name))");

        createMatches(db, "matches");

        db.execSQL("CREATE TABLE score_audit ("
                + "id TEXT PRIMARY KEY, "
                + "match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE, "
                + "changed_by TEXT REFERENCES users(id), "
                + "changed_at INTEGER NOT NULL, "
                + "action TEXT NOT NULL, "
                + "old_score TEXT NOT NULL DEFAULT '', "
                + "new_score TEXT NOT NULL DEFAULT '', "
                + "reason TEXT NOT NULL DEFAULT '')");

        createSettings(db);
        createAdminAudit(db);
        addVersion3Columns(db);
        addVersion4Schema(db);
        addVersion5Schema(db);
        addVersion6Schema(db);

        SeedData.insertSampleBranches(db);
        if (seedDevData) {
            DevData.insert(db);
            linkSportsToTemplates(db);
            // The development categories get full rule sets, so every scoring screen
            // can be tried out straight away.
            TemplateRules.fillMissingRules(db);
        }
    }

    // ----- Tables added in version 2 (also used by the upgrade below) -----

    private static void createBranches(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE branches ("
                + "id TEXT PRIMARY KEY, "
                + "name TEXT NOT NULL UNIQUE COLLATE NOCASE, "
                + "code TEXT NOT NULL DEFAULT '', "
                + "active INTEGER NOT NULL DEFAULT 1)");
    }

    private static void createUsers(SQLiteDatabase db, String tableName) {
        db.execSQL("CREATE TABLE " + tableName + " ("
                + "id TEXT PRIMARY KEY, "
                + "name TEXT NOT NULL, "
                + "email TEXT NOT NULL UNIQUE COLLATE NOCASE, "
                + "salt TEXT NOT NULL, "
                + "password_hash TEXT NOT NULL, "
                + "role TEXT NOT NULL "
                + "CHECK (role IN ('FACULTY','CORE','ASOO_CORE','CAPTAIN')), "
                + "active INTEGER NOT NULL DEFAULT 1, "
                + "branch_id TEXT REFERENCES branches(id))");
    }

    private static void createSettings(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE settings ("
                + "name TEXT PRIMARY KEY, "
                + "value INTEGER NOT NULL)");
        // Starting values. Faculty can change them on the settings screen.
        for (String key : AppSettings.ALL) {
            ContentValues cv = new ContentValues();
            cv.put("name", key);
            cv.put("value", 1);
            db.insertOrThrow("settings", null, cv);
        }
    }

    private static void createAdminAudit(SQLiteDatabase db) {
        // The actor's name is copied in, so the log survives the removal of an account.
        db.execSQL("CREATE TABLE admin_audit ("
                + "id TEXT PRIMARY KEY, "
                + "actor_id TEXT, "
                + "actor_name TEXT NOT NULL DEFAULT '', "
                + "changed_at INTEGER NOT NULL, "
                + "action TEXT NOT NULL, "
                + "detail TEXT NOT NULL DEFAULT '')");
    }

    static void writeAdminAudit(SQLiteDatabase db, String actorId, String actorName,
                                String action, String detail) {
        ContentValues cv = new ContentValues();
        cv.put("id", UUID.randomUUID().toString());
        if (actorId != null) {
            cv.put("actor_id", actorId);
        }
        cv.put("actor_name", actorName == null ? "" : actorName);
        cv.put("changed_at", System.currentTimeMillis());
        cv.put("action", action);
        cv.put("detail", detail);
        db.insertOrThrow("admin_audit", null, cv);
    }

    // ----- Upgrades -----

    /**
     * Android runs this inside a transaction. If any statement fails, every change made
     * here is rolled back, the database stays on its old version with its data intact,
     * and the exception reaches the screen that opened the database (see BaseActivity).
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            if (oldVersion < 2) {
                upgradeToVersion2(db);
            }
            if (oldVersion < 3) {
                upgradeToVersion3(db);
            }
            if (oldVersion < 4) {
                upgradeToVersion4(db);
            }
            if (oldVersion < 5) {
                upgradeToVersion5(db);
            }
            if (oldVersion < 6) {
                addVersion6Schema(db);
                writeAdminAudit(db, null, "System", "DATABASE_UPGRADE",
                        "Upgraded from version 5 to 6. Heats, finals and throwing events "
                                + "are now available. No existing data was changed.");
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Database upgrade from version " + oldVersion + " failed", e);
            throw e;
        }
    }

    /**
     * Version 1 to 2. No sports, categories, registrations, matches or score history
     * are touched. Only the users table is rebuilt, because SQLite cannot change the
     * list of allowed roles of an existing table.
     *
     *   Core and Asoo Core accounts keep their id, password and role.
     *   Viewer accounts no longer exist as a role:
     *     - a Viewer that some match or score-history row points to is kept as a
     *       DEACTIVATED Asoo Core, so those rows stay valid and nobody gains access;
     *     - every other Viewer account is removed.
     *   No Faculty account is created. The first one is made on the set-up screen.
     */
    private void upgradeToVersion2(SQLiteDatabase db) {
        createBranches(db);
        SeedData.insertSampleBranches(db);

        long before = DatabaseUtils.queryNumEntries(db, "users");

        createUsers(db, "users_new");
        db.execSQL("INSERT INTO users_new (id, name, email, salt, password_hash, role, active) "
                + "SELECT id, name, email, salt, password_hash, role, 1 FROM users "
                + "WHERE role IN ('CORE','ASOO_CORE')");
        db.execSQL("INSERT INTO users_new (id, name, email, salt, password_hash, role, active) "
                + "SELECT id, name, email, salt, password_hash, 'ASOO_CORE', 0 FROM users "
                + "WHERE role NOT IN ('CORE','ASOO_CORE') AND ("
                + "id IN (SELECT assigned_to FROM matches WHERE assigned_to IS NOT NULL) "
                + "OR id IN (SELECT updated_by FROM matches WHERE updated_by IS NOT NULL) "
                + "OR id IN (SELECT changed_by FROM score_audit WHERE changed_by IS NOT NULL))");
        long kept = DatabaseUtils.queryNumEntries(db, "users_new");
        long deactivated = DatabaseUtils.queryNumEntries(db, "users_new", "active = 0");

        db.execSQL("DROP TABLE users");
        db.execSQL("ALTER TABLE users_new RENAME TO users");

        createSettings(db);
        createAdminAudit(db);
        writeAdminAudit(db, null, "System", "DATABASE_UPGRADE",
                "Upgraded from version 1 to 2. Accounts before: " + before
                        + ". Kept: " + kept + " (" + deactivated
                        + " former Viewer accounts kept deactivated because match history "
                        + "refers to them). Viewer accounts removed: " + (before - kept) + ".");
    }

    // ----- Version 3 -----

    /** Name of the branch given to registrations that were made before branches existed. */
    public static final String UNASSIGNED_BRANCH = "Unassigned";

    /** New columns of version 3. Used for new databases and for upgrades alike. */
    private static void addVersion3Columns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE registrations ADD COLUMN branch_id TEXT REFERENCES branches(id)");
        // 1 to 4, or 0 when the year is not known (registrations from before version 3).
        db.execSQL("ALTER TABLE registrations ADD COLUMN year INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE registrations ADD COLUMN team_label TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE registrations ADD COLUMN logo_path TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE users ADD COLUMN failed_logins INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE users ADD COLUMN locked_until INTEGER NOT NULL DEFAULT 0");
    }

    /**
     * Version 2 to 3. Only adds columns, so no existing value is changed or removed.
     * Registrations that already exist have no branch, so they are attached to a branch
     * called "Unassigned" (deactivated, so it cannot be chosen for new registrations).
     * An organiser can then open each one and choose its real branch and year.
     */
    private void upgradeToVersion3(SQLiteDatabase db) {
        addVersion3Columns(db);
        long existing = DatabaseUtils.queryNumEntries(db, "registrations");
        if (existing > 0) {
            ContentValues branch = new ContentValues();
            branch.put("id", UUID.randomUUID().toString());
            branch.put("name", UNASSIGNED_BRANCH);
            branch.put("code", "");
            branch.put("active", 0);
            db.insertWithOnConflict("branches", null, branch, SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("UPDATE registrations SET branch_id = "
                    + "(SELECT id FROM branches WHERE name = ?) WHERE branch_id IS NULL",
                    new Object[]{UNASSIGNED_BRANCH});
        }
        writeAdminAudit(db, null, "System", "DATABASE_UPGRADE",
                "Upgraded from version 2 to 3. " + existing + " existing registrations were "
                        + "placed under the branch \"" + UNASSIGNED_BRANCH + "\" until an "
                        + "organiser sets their real branch and year.");
    }

    // ----- Version 4 -----

    /** Template table and the new columns. Used for new databases and for upgrades alike. */
    private void addVersion4Schema(SQLiteDatabase db) {
        TemplateStore.createTable(db);
        TemplateStore.loadCatalogue(db, appContext);
        db.execSQL("ALTER TABLE sports ADD COLUMN template_key TEXT NOT NULL DEFAULT 'custom'");
        // SCORE is how every match was scored before version 4: one score for each side.
        db.execSQL("ALTER TABLE categories ADD COLUMN engine TEXT NOT NULL DEFAULT 'SCORE'");
        db.execSQL("ALTER TABLE categories ADD COLUMN rules TEXT NOT NULL DEFAULT ''");
    }

    /** A sport whose name is the name of a catalogue template is attached to it. */
    private static void linkSportsToTemplates(SQLiteDatabase db) {
        db.execSQL("UPDATE sports SET template_key = "
                + "(SELECT t.template_key FROM sport_templates t "
                + " WHERE t.name = sports.name COLLATE NOCASE) "
                + "WHERE EXISTS (SELECT 1 FROM sport_templates t "
                + " WHERE t.name = sports.name COLLATE NOCASE)");
    }

    /**
     * Version 3 to 4. Only adds a table and columns.
     * Existing sports are attached to the catalogue template of the same name; any other
     * sport becomes a "custom" sport. Existing categories are left exactly as they were:
     * they keep the single-score engine and have no stored rules until an organiser
     * opens and saves them, so no existing score changes its meaning.
     */
    private void upgradeToVersion4(SQLiteDatabase db) {
        addVersion4Schema(db);
        linkSportsToTemplates(db);
        long linked = DatabaseUtils.queryNumEntries(db, "sports", "template_key <> 'custom'");
        long sports = DatabaseUtils.queryNumEntries(db, "sports");
        writeAdminAudit(db, null, "System", "DATABASE_UPGRADE",
                "Upgraded from version 3 to 4. Sport catalogue loaded. " + linked + " of "
                        + sports + " existing sports matched a catalogue template; the others "
                        + "are custom sports. Existing categories keep their scoring.");
    }

    // ----- Version 5 -----

    /** The matches table as first created, with PAUSED added to the allowed statuses. */
    private static void createMatches(SQLiteDatabase db, String tableName) {
        db.execSQL("CREATE TABLE " + tableName + " ("
                + "id TEXT PRIMARY KEY, "
                + "category_id TEXT NOT NULL REFERENCES categories(id), "
                + "team_a_id TEXT NOT NULL REFERENCES registrations(id), "
                + "team_b_id TEXT NOT NULL REFERENCES registrations(id), "
                + "match_date TEXT NOT NULL, "
                + "match_time TEXT NOT NULL DEFAULT '', "
                + "venue TEXT NOT NULL DEFAULT '', "
                + "round TEXT NOT NULL, "
                + "status TEXT NOT NULL "
                + "CHECK (status IN ('UPCOMING','LIVE','PAUSED','COMPLETED','POSTPONED')), "
                + "score_a INTEGER, "
                + "score_b INTEGER, "
                + "score_note TEXT NOT NULL DEFAULT '', "
                + "is_final INTEGER NOT NULL DEFAULT 0, "
                + "assigned_to TEXT REFERENCES users(id), "
                + "updated_at INTEGER NOT NULL, "
                + "updated_by TEXT REFERENCES users(id), "
                + "version INTEGER NOT NULL DEFAULT 1, "
                + "CHECK (team_a_id <> team_b_id))");
    }

    /** New columns and tables of version 5. Used for new databases and for upgrades alike. */
    private static void addVersion5Schema(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE matches ADD COLUMN external_url TEXT NOT NULL DEFAULT ''");
        // Number of the half or quarter in play, 0 when not used.
        db.execSQL("ALTER TABLE matches ADD COLUMN current_period INTEGER NOT NULL DEFAULT 0");
        // Penalty shoot-out, only filled in when one was needed.
        db.execSQL("ALTER TABLE matches ADD COLUMN pen_a INTEGER");
        db.execSQL("ALTER TABLE matches ADD COLUMN pen_b INTEGER");

        db.execSQL("CREATE TABLE match_periods ("
                + "id TEXT PRIMARY KEY, "
                + "match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE, "
                + "number INTEGER NOT NULL, "
                + "side TEXT NOT NULL DEFAULT '', "
                + "score_a INTEGER NOT NULL DEFAULT 0, "
                + "score_b INTEGER NOT NULL DEFAULT 0, "
                + "wickets INTEGER NOT NULL DEFAULT 0, "
                + "balls INTEGER NOT NULL DEFAULT 0, "
                + "extras INTEGER NOT NULL DEFAULT 0, "
                + "tb_a INTEGER NOT NULL DEFAULT 0, "
                + "tb_b INTEGER NOT NULL DEFAULT 0, "
                + "pt_a INTEGER NOT NULL DEFAULT 0, "
                + "pt_b INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (match_id, number))");

        db.execSQL("CREATE TABLE match_events ("
                + "id TEXT PRIMARY KEY, "
                + "match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE, "
                + "minute INTEGER NOT NULL DEFAULT 0, "
                + "side TEXT NOT NULL, "
                + "type TEXT NOT NULL, "
                + "player TEXT NOT NULL DEFAULT '', "
                + "created_by TEXT REFERENCES users(id), "
                + "created_at INTEGER NOT NULL)");
    }

    /**
     * Version 4 to 5. The matches table is rebuilt only to allow the new PAUSED status
     * (SQLite cannot change the list of allowed values of an existing table). Every
     * match row is copied across unchanged, with the same id, so the score history that
     * points to it stays attached. Then the new columns and tables are added.
     */
    private void upgradeToVersion5(SQLiteDatabase db) {
        long before = DatabaseUtils.queryNumEntries(db, "matches");
        String columns = "id, category_id, team_a_id, team_b_id, match_date, match_time, venue, "
                + "round, status, score_a, score_b, score_note, is_final, assigned_to, "
                + "updated_at, updated_by, version";
        createMatches(db, "matches_new");
        db.execSQL("INSERT INTO matches_new (" + columns + ") SELECT " + columns
                + " FROM matches");
        db.execSQL("DROP TABLE matches");
        db.execSQL("ALTER TABLE matches_new RENAME TO matches");
        long after = DatabaseUtils.queryNumEntries(db, "matches");
        if (after != before) {
            // Throwing here rolls the whole upgrade back.
            throw new IllegalStateException("Match rows were lost while upgrading: " + before
                    + " before, " + after + " after");
        }
        addVersion5Schema(db);
        writeAdminAudit(db, null, "System", "DATABASE_UPGRADE",
                "Upgraded from version 4 to 5. " + after + " matches kept. Set-by-set scores, "
                        + "match events, the PAUSED status and CricHeroes links are now "
                        + "available.");
    }

    // ----- Version 6 -----

    /** Tables for events with many entrants. Only new tables: nothing existing changes. */
    private static void addVersion6Schema(SQLiteDatabase db) {
        // One heat or final of a race, relay, swimming or throwing category.
        db.execSQL("CREATE TABLE event_rounds ("
                + "id TEXT PRIMARY KEY, "
                + "category_id TEXT NOT NULL REFERENCES categories(id), "
                + "stage TEXT NOT NULL CHECK (stage IN ('HEAT','FINAL')), "
                + "heat_number INTEGER NOT NULL DEFAULT 0, "
                + "event_date TEXT NOT NULL, "
                + "event_time TEXT NOT NULL DEFAULT '', "
                + "venue TEXT NOT NULL DEFAULT '', "
                + "status TEXT NOT NULL "
                + "CHECK (status IN ('UPCOMING','LIVE','PAUSED','COMPLETED','POSTPONED')), "
                + "is_final INTEGER NOT NULL DEFAULT 0, "
                + "assigned_to TEXT REFERENCES users(id), "
                + "updated_at INTEGER NOT NULL, "
                + "updated_by TEXT REFERENCES users(id), "
                + "version INTEGER NOT NULL DEFAULT 1, "
                + "UNIQUE (category_id, stage, heat_number))");

        // One entrant of a heat or final. time_ms is thousandths of a second,
        // best_cm is centimetres: whole numbers, so results compare exactly.
        db.execSQL("CREATE TABLE event_entries ("
                + "id TEXT PRIMARY KEY, "
                + "round_id TEXT NOT NULL REFERENCES event_rounds(id) ON DELETE CASCADE, "
                + "registration_id TEXT NOT NULL REFERENCES registrations(id), "
                + "lane INTEGER NOT NULL DEFAULT 0, "
                + "time_ms INTEGER, "
                + "result_status TEXT NOT NULL DEFAULT '', "
                + "position INTEGER, "
                + "qualified INTEGER NOT NULL DEFAULT 0, "
                + "best_cm INTEGER, "
                + "UNIQUE (round_id, registration_id))");

        db.execSQL("CREATE TABLE event_attempts ("
                + "id TEXT PRIMARY KEY, "
                + "entry_id TEXT NOT NULL REFERENCES event_entries(id) ON DELETE CASCADE, "
                + "number INTEGER NOT NULL, "
                + "distance_cm INTEGER, "
                + "foul INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (entry_id, number))");

        db.execSQL("CREATE TABLE event_audit ("
                + "id TEXT PRIMARY KEY, "
                + "round_id TEXT NOT NULL REFERENCES event_rounds(id) ON DELETE CASCADE, "
                + "changed_by TEXT REFERENCES users(id), "
                + "changed_at INTEGER NOT NULL, "
                + "action TEXT NOT NULL, "
                + "detail TEXT NOT NULL DEFAULT '', "
                + "reason TEXT NOT NULL DEFAULT '')");
    }
}
