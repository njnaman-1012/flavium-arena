package in.flavium.arena.data;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.User;
import java.util.UUID;
import static in.flavium.arena.data.Sql.*;

/**
 * Small helpers for working with SQLite that every class of the data layer uses:
 * reading a column safely, counting rows, and turning a row into a model object.
 */
final class Sql {

    private Sql() {
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static int count(SQLiteDatabase db, String table, String where, String... args) {
        return (int) DatabaseUtils.queryNumEntries(db, table, where, args);
    }

    /** Text column, with NULL returned as an empty string. */
    static String text(Cursor c, String column) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? "" : c.getString(index);
    }

    /** Text column that may really be NULL (used for optional ids). */
    static String textOrNull(Cursor c, String column) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? null : c.getString(index);
    }

    static int number(Cursor c, String column) {
        return c.getInt(c.getColumnIndexOrThrow(column));
    }

    static Integer numberOrNull(Cursor c, String column) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? null : c.getInt(index);
    }

    static User readUser(Cursor c) {
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

    static Sport readSport(Cursor c) {
        Sport s = new Sport();
        s.id = text(c, "id");
        s.name = text(c, "name");
        s.description = text(c, "description");
        s.templateKey = text(c, "template_key");
        return s;
    }

    static Category readCategory(Cursor c) {
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
    static Registration readRegistration(Cursor c) {
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
        r.sportId = text(c, "sport_id");
        return r;
    }

    static Match readMatch(Cursor c) {
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
        m.sportId = text(c, "sport_id");
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
