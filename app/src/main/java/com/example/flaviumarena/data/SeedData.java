package com.example.flaviumarena.data;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import java.util.UUID;

/**
 * Starting data that every new database gets: a few SAMPLE branches.
 * They are placeholders, not the college's official list; Faculty can rename, add and
 * deactivate branches. No accounts are created here. Development accounts and sample
 * fixtures are in DevData, which exists only in debug builds.
 */
public final class SeedData {

    private static final String[][] SAMPLE_BRANCHES = {
            {"Computer Engineering", "CE"},
            {"Information Technology", "IT"},
            {"Mechanical Engineering", "ME"},
            {"Electronics and Telecommunication", "EXTC"},
            {"Civil Engineering", "CIVIL"}};

    private SeedData() {
    }

    static void insertSampleBranches(SQLiteDatabase db) {
        for (String[] branch : SAMPLE_BRANCHES) {
            ContentValues cv = new ContentValues();
            cv.put("id", UUID.randomUUID().toString());
            cv.put("name", branch[0]);
            cv.put("code", branch[1]);
            cv.put("active", 1);
            db.insertOrThrow("branches", null, cv);
        }
    }
}
