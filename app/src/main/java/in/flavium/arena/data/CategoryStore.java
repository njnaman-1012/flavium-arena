package in.flavium.arena.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import in.flavium.arena.auth.AccessControl;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.SportTemplate;
import in.flavium.arena.model.User;
import in.flavium.arena.util.DateUtil;
import java.util.ArrayList;
import java.util.List;

import static in.flavium.arena.data.Sql.*;

/**
 * Categories of a sport and their rules: reading, validating against the sport's
 * template, saving and deleting. SqliteArenaRepository hands these calls on.
 */
final class CategoryStore extends Store {

    CategoryStore(SqliteArenaRepository repo, DbHelper helper) {
        super(repo, helper);
    }

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

    public String saveCategory(User actorArg, Category category) {
        User actor = repo.actorIn(actorArg,
                category.id == null ? category.sportId : repo.sportOfCategory(category.id));
        AccessControl.require(AccessControl.canManageCategories(actor), actor,
                "add or edit categories");

        String name = clean(category.name);
        String deadline = clean(category.deadline);
        Sport sport = repo.getSport(category.sportId);
        if (sport == null) {
            throw new ValidationException("The sport of this category no longer exists.");
        }

        // When rules are given, they are checked against the sport's template and the
        // classic columns (type, team size, format, points, engine) are worked out
        // from them. A category saved without rules keeps the values it was given.
        Category stored = category.id == null ? null : getCategory(category.id);
        String rulesText = clean(category.rules);
        if (!rulesText.isEmpty()) {
            SportTemplate template = repo.templateOf(sport);
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
            repo.audit(actor, "RULES_CHANGED", "Rules of \"" + name + "\" changed after its "
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

    public void deleteCategory(User actorArg, String id) {
        User actor = repo.actorIn(actorArg, repo.sportOfCategory(id));
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
}
