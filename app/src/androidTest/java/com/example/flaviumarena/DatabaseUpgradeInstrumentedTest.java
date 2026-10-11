package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.DbHelper;
import com.example.flaviumarena.data.PermissionDeniedException;
import com.example.flaviumarena.data.SqliteArenaRepository;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.util.PasswordUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Builds a database exactly as version 1 of the app created it, fills it with data,
 * then lets DbHelper upgrade it and checks what happened to every kind of record.
 * Uses its own database file, so the app's real database is not touched.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseUpgradeInstrumentedTest {

    private static final String DB_FILE = "upgrade_test.db";
    private static final String PASSWORD = "Old@12345";

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DB_FILE);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DB_FILE);
    }

    // ----- The version 1 schema, copied from the first release of DbHelper -----

    private SQLiteDatabase createVersion1Database(boolean includeUsersTable) {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath(DB_FILE), null);
        if (includeUsersTable) {
            db.execSQL("CREATE TABLE users (id TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "email TEXT NOT NULL UNIQUE COLLATE NOCASE, salt TEXT NOT NULL, "
                    + "password_hash TEXT NOT NULL, "
                    + "role TEXT NOT NULL CHECK (role IN ('CORE','ASOO_CORE','VIEWER')))");
        }
        db.execSQL("CREATE TABLE sports (id TEXT PRIMARY KEY, "
                + "name TEXT NOT NULL UNIQUE COLLATE NOCASE, "
                + "description TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE categories (id TEXT PRIMARY KEY, "
                + "sport_id TEXT NOT NULL REFERENCES sports(id), "
                + "name TEXT NOT NULL COLLATE NOCASE, "
                + "type TEXT NOT NULL CHECK (type IN ('INDIVIDUAL','TEAM')), "
                + "min_size INTEGER NOT NULL, max_size INTEGER NOT NULL, "
                + "deadline TEXT NOT NULL DEFAULT '', "
                + "format TEXT NOT NULL CHECK (format IN ('LEAGUE','KNOCKOUT')), "
                + "pts_win INTEGER NOT NULL, pts_draw INTEGER NOT NULL, "
                + "pts_loss INTEGER NOT NULL, UNIQUE (sport_id, name))");
        db.execSQL("CREATE TABLE registrations (id TEXT PRIMARY KEY, "
                + "category_id TEXT NOT NULL REFERENCES categories(id), "
                + "name TEXT NOT NULL COLLATE NOCASE, "
                + "contact_name TEXT NOT NULL DEFAULT '', "
                + "contact_phone TEXT NOT NULL DEFAULT '', members TEXT NOT NULL DEFAULT '', "
                + "status TEXT NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')), "
                + "created_at INTEGER NOT NULL, UNIQUE (category_id, name))");
        db.execSQL("CREATE TABLE matches (id TEXT PRIMARY KEY, "
                + "category_id TEXT NOT NULL REFERENCES categories(id), "
                + "team_a_id TEXT NOT NULL REFERENCES registrations(id), "
                + "team_b_id TEXT NOT NULL REFERENCES registrations(id), "
                + "match_date TEXT NOT NULL, match_time TEXT NOT NULL DEFAULT '', "
                + "venue TEXT NOT NULL DEFAULT '', round TEXT NOT NULL, "
                + "status TEXT NOT NULL "
                + "CHECK (status IN ('UPCOMING','LIVE','COMPLETED','POSTPONED')), "
                + "score_a INTEGER, score_b INTEGER, score_note TEXT NOT NULL DEFAULT '', "
                + "is_final INTEGER NOT NULL DEFAULT 0, "
                + "assigned_to TEXT REFERENCES users(id), updated_at INTEGER NOT NULL, "
                + "updated_by TEXT REFERENCES users(id), version INTEGER NOT NULL DEFAULT 1, "
                + "CHECK (team_a_id <> team_b_id))");
        db.execSQL("CREATE TABLE score_audit (id TEXT PRIMARY KEY, "
                + "match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE, "
                + "changed_by TEXT REFERENCES users(id), changed_at INTEGER NOT NULL, "
                + "action TEXT NOT NULL, old_score TEXT NOT NULL DEFAULT '', "
                + "new_score TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '')");
        db.setVersion(1);
        return db;
    }

    private void insertVersion1Data(SQLiteDatabase db) {
        String hash = PasswordUtil.legacyHash(PASSWORD, "salt");
        String[][] users = {
                {"u-core", "Old Core", "oldcore@flavium.test", "CORE"},
                {"u-asoo", "Old Asoo", "oldasoo@flavium.test", "ASOO_CORE"},
                {"u-viewer-used", "Viewer In History", "viewer1@flavium.test", "VIEWER"},
                {"u-viewer-plain", "Plain Viewer", "viewer2@flavium.test", "VIEWER"}};
        for (String[] u : users) {
            db.execSQL("INSERT INTO users VALUES (?, ?, ?, 'salt', ?, ?)",
                    new Object[]{u[0], u[1], u[2], hash, u[3]});
        }
        db.execSQL("INSERT INTO sports VALUES ('s1', 'Football', 'Outdoor')");
        db.execSQL("INSERT INTO categories VALUES ('c1', 's1', 'Men', 'TEAM', 7, 10, '', "
                + "'LEAGUE', 3, 1, 0)");
        db.execSQL("INSERT INTO registrations VALUES ('r1', 'c1', 'Falcons', 'A', '900', "
                + "'A\nB', 'APPROVED', 1)");
        db.execSQL("INSERT INTO registrations VALUES ('r2', 'c1', 'Titans', 'C', '901', "
                + "'C\nD', 'APPROVED', 1)");
        db.execSQL("INSERT INTO matches VALUES ('m1', 'c1', 'r1', 'r2', '2026-10-09', '16:00', "
                + "'Main Ground', 'Round 1', 'COMPLETED', 2, 1, '', 1, 'u-asoo', 5, "
                + "'u-core', 4)");
        // This row is why one of the Viewer accounts must survive the upgrade.
        db.execSQL("INSERT INTO score_audit VALUES ('a1', 'm1', 'u-viewer-used', 5, "
                + "'SCORE_UPDATE', '1 - 0', '2 - 1', '')");
    }

    @Test
    public void upgradeKeepsFestivalDataAndConvertsAccounts() {
        SQLiteDatabase old = createVersion1Database(true);
        insertVersion1Data(old);
        old.close();

        DbHelper helper = new DbHelper(context, DB_FILE, false);
        SQLiteDatabase db = helper.getWritableDatabase();
        ArenaRepository repo = new SqliteArenaRepository(helper);

        assertEquals(DbHelper.DB_VERSION, db.getVersion());

        // Festival data is untouched.
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "sports"));
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "categories"));
        assertEquals(2, DatabaseUtils.queryNumEntries(db, "registrations"));
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "score_audit"));
        List<Match> matches = repo.getMatches(null);
        assertEquals(1, matches.size());
        Match m = matches.get(0);
        assertEquals("m1", m.id);
        assertEquals(Integer.valueOf(2), m.scoreA);
        assertEquals(Integer.valueOf(1), m.scoreB);
        assertTrue(m.isFinal);
        assertEquals(4, m.version);
        assertEquals("Old Asoo", m.assignedName);
        assertEquals("Old Core", m.updatedByName);

        // Existing registrations keep every value and are placed under "Unassigned".
        List<Registration> teams = repo.getRegistrations(null, "c1");
        assertEquals(2, teams.size());
        for (Registration r : teams) {
            assertEquals(DbHelper.UNASSIGNED_BRANCH, r.branchName);
            assertEquals(0, r.year);
            assertEquals("", r.contactPhone); // still hidden from the public
        }
        assertEquals("Falcons", teams.get(0).name);
        assertEquals("900", DatabaseUtils.stringForQuery(db,
                "SELECT contact_phone FROM registrations WHERE id = 'r1'", null));

        // Core and Asoo Core keep their id, role and password.
        assertFalse(storedHash(db, "u-core").startsWith("pbkdf2$"));
        User core = repo.login("oldcore@flavium.test", PASSWORD, Role.CORE);
        assertEquals("u-core", core.id);
        assertTrue(core.active);
        // That first login replaced the weak stored value, and the password still works.
        assertTrue(storedHash(db, "u-core").startsWith("pbkdf2$"));
        assertEquals("u-core", repo.login("oldcore@flavium.test", PASSWORD, Role.CORE).id);
        User asoo = repo.login("oldasoo@flavium.test", PASSWORD, Role.ASOO_CORE);
        assertEquals("u-asoo", asoo.id);

        // The Viewer that appears in the score history is kept, but deactivated.
        User kept = repo.getUser("u-viewer-used");
        assertNotNull(kept);
        assertFalse(kept.active);
        try {
            repo.login("viewer1@flavium.test", PASSWORD, Role.ASOO_CORE);
            fail("A former Viewer account must not be able to log in");
        } catch (PermissionDeniedException expected) {
            // correct
        }
        // The other Viewer is gone.
        assertNull(repo.getUser("u-viewer-plain"));

        // No Faculty account appears by itself; new tables exist with their defaults.
        assertFalse(repo.hasFaculty());
        // Five sample branches plus the deactivated "Unassigned" one.
        assertEquals(6, repo.getBranches().size());
        assertFalse(repo.getBranch(teams.get(0).branchId).active);
        assertTrue(repo.getFlag("core_can_finalise"));
        assertTrue(repo.getFlag("core_can_appoint_captains"));
        // One log entry per upgrade step: 1 to 2, 2 to 3, 3 to 4, 4 to 5, and 5 to 6.
        assertEquals(5, DatabaseUtils.queryNumEntries(db, "admin_audit"));

        // Version 6 only added tables, which are empty.
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "event_rounds"));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "event_entries"));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "event_attempts"));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "event_audit"));
        assertTrue(repo.getRounds(null).isEmpty());

        // Version 5 rebuilt the matches table: the match and its history are still
        // attached to each other, and the new tables exist and are empty.
        assertEquals(1, repo.getScoreAudit(repo.login("oldcore@flavium.test", PASSWORD, Role.CORE), "m1").size());
        assertEquals(0, repo.getPeriods("m1").size());
        assertEquals(0, repo.getEvents("m1").size());
        assertEquals("", m.externalUrl);

        // Version 4: the catalogue is loaded, the existing sport is attached to its
        // template, and the existing category keeps the scoring it always had.
        assertTrue(repo.getTemplates().size() >= 15);
        assertEquals("football", repo.getSports().get(0).templateKey);
        Category oldCategory = repo.getCategory("c1");
        assertEquals("SCORE", oldCategory.engine);
        assertEquals("", oldCategory.rules);
        assertEquals("TEAM", oldCategory.type);
        assertEquals(7, oldCategory.minSize);

        // The rebuilt users table is still what matches point to: the foreign key check
        // reports no broken reference, and the rules are being enforced.
        assertEquals(0, db.rawQuery("PRAGMA foreign_key_check", null).getCount());
        assertEquals(1, DatabaseUtils.longForQuery(db, "PRAGMA foreign_keys", null));
        helper.close();
    }

    @Test
    public void failedUpgradeLeavesTheOldDatabaseUnchanged() {
        // A damaged version 1 database: the users table is missing, so the upgrade fails.
        SQLiteDatabase old = createVersion1Database(false);
        old.execSQL("INSERT INTO sports VALUES ('s1', 'Football', 'Outdoor')");
        old.close();

        DbHelper helper = new DbHelper(context, DB_FILE, false);
        try {
            helper.getWritableDatabase();
            fail("The upgrade should have failed");
        } catch (SQLiteException expected) {
            // correct
        }
        helper.close();

        // Everything the upgrade did was rolled back.
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                context.getDatabasePath(DB_FILE).getPath(), null, SQLiteDatabase.OPEN_READONLY);
        assertEquals(1, db.getVersion());
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "sports"));
        assertEquals(0, DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM sqlite_master WHERE name IN "
                        + "('branches','users_new','settings','admin_audit')", null));
        db.close();
    }

    @Test
    public void failureInTheSecondStepAlsoUndoesTheFirstStep() {
        // A version 1 database whose registrations table already has a column that
        // step 2 to 3 wants to add. Step 1 to 2 succeeds, step 2 to 3 fails.
        SQLiteDatabase old = createVersion1Database(true);
        insertVersion1Data(old);
        old.execSQL("ALTER TABLE registrations ADD COLUMN year INTEGER NOT NULL DEFAULT 0");
        old.close();

        DbHelper helper = new DbHelper(context, DB_FILE, false);
        try {
            helper.getWritableDatabase();
            fail("The upgrade should have failed");
        } catch (SQLiteException expected) {
            // correct
        }
        helper.close();

        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                context.getDatabasePath(DB_FILE).getPath(), null, SQLiteDatabase.OPEN_READONLY);
        assertEquals(1, db.getVersion());
        // All four accounts, including both Viewers, are still there.
        assertEquals(4, DatabaseUtils.queryNumEntries(db, "users"));
        assertEquals(2, DatabaseUtils.queryNumEntries(db, "users", "role = 'VIEWER'"));
        assertEquals(0, DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM sqlite_master WHERE name IN "
                        + "('branches','settings','admin_audit','sport_templates','match_periods','match_events','matches_new')", null));
        db.close();
    }

    private static String storedHash(SQLiteDatabase db, String userId) {
        return DatabaseUtils.stringForQuery(db,
                "SELECT password_hash FROM users WHERE id = ?", new String[]{userId});
    }

    @Test
    public void newInstallWithoutDevDataHasNoAccounts() {
        DbHelper helper = new DbHelper(context, DB_FILE, false);
        SQLiteDatabase db = helper.getWritableDatabase();
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "users"));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "sports"));
        assertEquals(5, DatabaseUtils.queryNumEntries(db, "branches"));
        helper.close();
    }
}
