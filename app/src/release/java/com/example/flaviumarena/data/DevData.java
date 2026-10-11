package com.example.flaviumarena.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

/**
 * Release builds contain no development accounts and no sample data.
 * The real class with that content is in app/src/debug and is not compiled here.
 */
public final class DevData {

    public static final boolean AVAILABLE = false;

    private DevData() {
    }

    public static String[] account(String role) {
        return null;
    }

    public static boolean exists(Context context, String role) {
        return false;
    }

    public static String createOrRepair(Context context, String role) {
        return "";
    }

    public static void insert(SQLiteDatabase db) {
        // Nothing to add in a release build.
    }
}
