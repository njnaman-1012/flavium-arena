package in.flavium.arena.sync;

import android.database.sqlite.SQLiteDatabase;

/**
 * Which tables are shared between devices, and the three small tables and the triggers
 * that make sharing possible.
 *
 * How a local change is noticed: every shared table has triggers that write one line
 * into sync_outbox when a row is added, changed or removed. No screen and no repository
 * method has to remember to do it, so a change can never be forgotten. The triggers do
 * nothing until synchronisation is switched on (sync_state "capture" = 1), so an
 * installation that is not connected to a server behaves exactly as before.
 */
public final class SyncSchema {

    /**
     * The shared tables, parents before children. Changes are sent and applied in this
     * order, and removals in the opposite order, so a row never arrives before the row
     * it refers to.
     */
    public static final String[] TABLES = {"branches", "users", "sports", "coach_sports",
            "categories", "registrations", "matches", "match_periods", "match_events",
            "score_audit", "event_rounds", "event_entries", "event_attempts", "event_audit",
            "settings", "admin_audit"};

    // Not shared, on purpose:
    //   sessions         a login session is decided by the server, never copied from a device
    //   sport_templates  the same catalogue is built into every installation
    //   sync_*           each device's own bookkeeping

    /**
     * Rows that only make sense together with another row: {table, the table it belongs
     * to, the column that points there}. When a change to a match is not accepted, the
     * history lines, sets and events written with it must not be sent either.
     */
    static final String[][] DEPENDENTS = {
            {"match_periods", "matches", "match_id"},
            {"match_events", "matches", "match_id"},
            {"score_audit", "matches", "match_id"},
            {"event_entries", "event_rounds", "round_id"},
            {"event_audit", "event_rounds", "round_id"},
            {"event_attempts", "event_entries", "entry_id"}};

    static final String STATE_CAPTURE = "capture";
    static final String STATE_CURSOR = "cursor";
    /** How far this device is connected: see SyncEngine.LINK_ values. */
    static final String STATE_LINK = "link";
    /** A random number that identifies this installation to the server. */
    static final String STATE_DEVICE = "device";
    /** When the last complete exchange with the server finished (milliseconds). */
    static final String STATE_LAST_SYNC = "last_sync";

    private SyncSchema() {
    }

    /** The columns that identify a row. Most tables have a single "id". */
    public static String[] keyColumns(String table) {
        if ("settings".equals(table)) {
            return new String[]{"name"};
        }
        if ("coach_sports".equals(table)) {
            return new String[]{"user_id", "sport_id"};
        }
        return new String[]{"id"};
    }

    /**
     * Columns that never leave the device. Password hashes and the lockout counters stay
     * local: sign-in is the server's job once the app is online.
     */
    public static boolean isLocalOnly(String table, String column) {
        return "users".equals(table) && ("salt".equals(column) || "password_hash".equals(column)
                || "failed_logins".equals(column) || "locked_until".equals(column));
    }

    /** Creates the bookkeeping tables and the triggers. Called for a new database and by the upgrade. */
    public static void create(SQLiteDatabase db) {
        // One line per changed row that the server has not confirmed yet. A row that
        // changes twice keeps one line (the newer one), so nothing is sent twice.
        db.execSQL("CREATE TABLE sync_outbox ("
                + "seq INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "table_name TEXT NOT NULL, "
                + "row_key TEXT NOT NULL, "
                + "deleted INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (table_name, row_key))");

        // The server's revision of each row as this device last saw it. Sending a change
        // quotes this number; the server refuses the change if its copy has moved on.
        db.execSQL("CREATE TABLE sync_rows ("
                + "table_name TEXT NOT NULL, "
                + "row_key TEXT NOT NULL, "
                + "rev INTEGER NOT NULL, "
                + "complete INTEGER NOT NULL DEFAULT 1, "
                + "PRIMARY KEY (table_name, row_key))");

        db.execSQL("CREATE TABLE sync_state ("
                + "name TEXT PRIMARY KEY, "
                + "value INTEGER NOT NULL)");
        db.execSQL("INSERT INTO sync_state (name, value) VALUES ('" + STATE_CAPTURE + "', 0)");
        db.execSQL("INSERT INTO sync_state (name, value) VALUES ('" + STATE_CURSOR + "', 0)");
        db.execSQL("INSERT INTO sync_state (name, value) VALUES ('" + STATE_LINK + "', 0)");
        db.execSQL("INSERT INTO sync_state (name, value) VALUES ('" + STATE_LAST_SYNC + "', 0)");
        // 62 random bits: two installations will not pick the same number.
        db.execSQL("INSERT INTO sync_state (name, value) VALUES ('" + STATE_DEVICE
                + "', abs(random()) + 1)");

        createTriggers(db);
    }

    /** Version 8 to 9: remembers whether a row is complete or only the public copy. */
    public static void upgradeToVersion9(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE sync_rows ADD COLUMN complete INTEGER NOT NULL DEFAULT 1");
    }

    /**
     * SQL that finds the sport a row belongs to, given the row's key; null for tables
     * that belong to no sport. A fixture's sport is its category's sport, a set's sport
     * is its fixture's, and so on.
     */
    static String sportSql(String table) {
        String ofCategory = "(SELECT sport_id FROM categories WHERE id = ";
        String ofMatch = "(SELECT c.sport_id FROM matches m JOIN categories c "
                + "ON c.id = m.category_id WHERE m.id = ";
        String ofRound = "(SELECT c.sport_id FROM event_rounds r JOIN categories c "
                + "ON c.id = r.category_id WHERE r.id = ";
        switch (table) {
            case "categories":
                return "SELECT sport_id FROM categories WHERE id = ?";
            case "registrations":
            case "matches":
            case "event_rounds":
                return "SELECT " + ofCategory + "t.category_id) FROM " + table + " t WHERE t.id = ?";
            case "match_periods":
            case "match_events":
            case "score_audit":
                return "SELECT " + ofMatch + "t.match_id) FROM " + table + " t WHERE t.id = ?";
            case "event_entries":
            case "event_audit":
                return "SELECT " + ofRound + "t.round_id) FROM " + table + " t WHERE t.id = ?";
            case "event_attempts":
                return "SELECT " + ofRound + "e.round_id) FROM event_attempts t "
                        + "JOIN event_entries e ON e.id = t.entry_id WHERE t.id = ?";
            default:
                return null;
        }
    }

    /**
     * (Re)creates the triggers. A migration that rebuilds a shared table (DROP and
     * RENAME) loses that table's triggers and must call this afterwards.
     */
    public static void createTriggers(SQLiteDatabase db) {
        String capturing = "(SELECT value FROM sync_state WHERE name = '" + STATE_CAPTURE + "') = 1";
        for (String table : TABLES) {
            String[] keys = keyColumns(table);
            // Changing only a local-only column (a wrong-password counter) is not a change
            // anyone else needs to hear about.
            String updateOf = "users".equals(table)
                    ? "UPDATE OF name, email, role, active, branch_id" : "UPDATE";
            trigger(db, table, "ins", "INSERT", capturing, key("NEW", keys), 0);
            trigger(db, table, "upd", updateOf, capturing, key("NEW", keys), 0);
            trigger(db, table, "del", "DELETE", capturing, key("OLD", keys), 1);
        }
    }

    private static void trigger(SQLiteDatabase db, String table, String suffix, String event,
                                String when, String keyExpression, int deleted) {
        String name = "sync_" + table + "_" + suffix;
        db.execSQL("DROP TRIGGER IF EXISTS " + name);
        db.execSQL("CREATE TRIGGER " + name + " AFTER " + event + " ON " + table
                + " WHEN " + when + " BEGIN "
                + "INSERT OR REPLACE INTO sync_outbox (table_name, row_key, deleted) "
                + "VALUES ('" + table + "', " + keyExpression + ", " + deleted + "); END");
    }

    /** SQL for the key of a row: the id, or the key columns joined with "|". */
    private static String key(String row, String[] columns) {
        StringBuilder sql = new StringBuilder();
        for (String column : columns) {
            sql.append(sql.length() > 0 ? " || '|' || " : "").append(row).append(".").append(column);
        }
        return sql.toString();
    }
}
