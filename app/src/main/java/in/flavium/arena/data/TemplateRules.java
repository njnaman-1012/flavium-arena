package in.flavium.arena.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import in.flavium.arena.model.Category;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.SportTemplate;

/**
 * Gives full rule sets to categories that were stored without any.
 * Used only for the development sample data when a new database is created.
 */
final class TemplateRules {

    private TemplateRules() {
    }

    static void fillMissingRules(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT c.id, c.type, c.min_size, c.max_size, c.format, "
                + "c.pts_win, c.pts_draw, c.pts_loss, s.template_key "
                + "FROM categories c JOIN sports s ON s.id = c.sport_id "
                + "WHERE c.rules = ''", null);
        try {
            while (c.moveToNext()) {
                SportTemplate template = TemplateStore.read(db, c.getString(8));
                if (template == null) {
                    continue;
                }
                Category category = new Category();
                category.type = c.getString(1);
                category.minSize = c.getInt(2);
                category.maxSize = c.getInt(3);
                category.format = c.getString(4);
                category.ptsWin = c.getInt(5);
                category.ptsDraw = c.getInt(6);
                category.ptsLoss = c.getInt(7);

                RuleSet rules = CategoryRules.fromColumns(template, category);
                ContentValues cv = new ContentValues();
                cv.put("rules", rules.toText());
                cv.put("engine", template.engineFor(rules));
                db.update("categories", cv, "id = ?", new String[]{c.getString(0)});
            }
        } finally {
            c.close();
        }
    }
}
