package com.example.flaviumarena.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.flaviumarena.model.RuleField;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.SportTemplate;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Sport templates in the database.
 *
 * The catalogue ships as assets/sport_templates.json. When the database is created or
 * upgraded, every template in that file is copied into the sport_templates table, one
 * row per template with its JSON text. From then on the app reads templates only from
 * the table, so the catalogue is data that can be changed without touching the screens.
 */
public final class TemplateStore {

    private static final String ASSET = "sport_templates.json";

    private TemplateStore() {
    }

    static void createTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sport_templates ("
                + "template_key TEXT PRIMARY KEY, "
                + "name TEXT NOT NULL, "
                + "sort_order INTEGER NOT NULL, "
                + "json TEXT NOT NULL)");
    }

    /** Copies the catalogue file into the table, replacing templates with the same key. */
    static void loadCatalogue(SQLiteDatabase db, Context context) {
        try {
            JSONArray templates = new JSONObject(readAsset(context)).getJSONArray("templates");
            for (int i = 0; i < templates.length(); i++) {
                JSONObject template = templates.getJSONObject(i);
                ContentValues cv = new ContentValues();
                cv.put("template_key", template.getString("key"));
                cv.put("name", template.getString("name"));
                cv.put("sort_order", i);
                cv.put("json", template.toString());
                db.insertWithOnConflict("sport_templates", null, cv,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("The sport catalogue could not be loaded", e);
        }
    }

    private static String readAsset(Context context) throws IOException {
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static List<SportTemplate> readAll(SQLiteDatabase db) {
        List<SportTemplate> list = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT json FROM sport_templates ORDER BY sort_order", null);
        try {
            while (c.moveToNext()) {
                list.add(parse(c.getString(0)));
            }
        } finally {
            c.close();
        }
        return list;
    }

    static SportTemplate read(SQLiteDatabase db, String key) {
        if (key == null) {
            return null;
        }
        Cursor c = db.rawQuery("SELECT json FROM sport_templates WHERE template_key = ?",
                new String[]{key});
        try {
            return c.moveToFirst() ? parse(c.getString(0)) : null;
        } finally {
            c.close();
        }
    }

    /**
     * Two questions that every sport has, so they are added here instead of being
     * repeated in each template: how many teams a branch may enter, and how many
     * entries the category accepts.
     */
    private static void addRegistrationLimits(SportTemplate t) {
        if (t.field(RuleSet.TEAMS_PER_BRANCH) == null) {
            RuleField teams = new RuleField();
            teams.key = RuleSet.TEAMS_PER_BRANCH;
            teams.label = "Teams one branch may enter per year";
            teams.type = RuleField.TYPE_NUMBER;
            teams.defaultValue = "1";
            teams.hint = "Team events only. With more than 1, extra teams need a label "
                    + "such as Team B.";
            teams.min = 1;
            teams.max = 10;
            t.fields.add(teams);
        }
        if (t.field(RuleSet.MAX_ENTRIES) == null) {
            RuleField capacity = new RuleField();
            capacity.key = RuleSet.MAX_ENTRIES;
            capacity.label = "Most entries accepted (0 = no limit)";
            capacity.type = RuleField.TYPE_NUMBER;
            capacity.defaultValue = "0";
            capacity.hint = "Rejected registrations do not count.";
            capacity.min = 0;
            capacity.max = 500;
            t.fields.add(capacity);
        }
    }

    /** Turns the stored JSON text of one template into objects. */
    static SportTemplate parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            SportTemplate t = new SportTemplate();
            t.key = o.getString("key");
            t.name = o.getString("name");
            t.description = o.optString("description");
            t.picture = o.optString("picture");
            t.engine = o.optString("engine", SportTemplate.ENGINE_SCORE);
            t.source = o.optString("source");

            JSONArray fields = o.getJSONArray("fields");
            for (int i = 0; i < fields.length(); i++) {
                JSONObject f = fields.getJSONObject(i);
                RuleField field = new RuleField();
                field.key = f.getString("key");
                field.label = f.optString("label");
                field.type = f.getString("type");
                field.defaultValue = f.optString("default");
                field.hint = f.optString("hint");
                field.min = f.optInt("min", 0);
                field.max = f.optInt("max", Integer.MAX_VALUE);
                JSONArray options = f.optJSONArray("options");
                for (int k = 0; options != null && k < options.length(); k++) {
                    field.options.add(options.getString(k));
                }
                if (f.has("showIfKey")) {
                    field.showIfKey = f.getString("showIfKey");
                    JSONArray values = f.getJSONArray("showIfValues");
                    for (int k = 0; k < values.length(); k++) {
                        field.showIfValues.add(values.getString(k));
                    }
                }
                t.fields.add(field);
            }

            JSONArray presets = o.optJSONArray("presets");
            for (int i = 0; presets != null && i < presets.length(); i++) {
                JSONObject p = presets.getJSONObject(i);
                SportTemplate.Preset preset = new SportTemplate.Preset();
                preset.name = p.getString("name");
                JSONObject values = p.getJSONObject("values");
                for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    preset.values.put(key, values.getString(key));
                }
                t.presets.add(preset);
            }
            addRegistrationLimits(t);
            return t;
        } catch (JSONException e) {
            throw new IllegalStateException("A sport template is damaged", e);
        }
    }
}
