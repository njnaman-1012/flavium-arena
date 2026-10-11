package com.example.flaviumarena.data;

import android.content.Context;

/**
 * Gives every screen the same repository object.
 * This is the single place to change when the app moves from SQLite to an online database.
 */
public final class RepositoryProvider {

    private static ArenaRepository instance;

    private RepositoryProvider() {
    }

    public static synchronized ArenaRepository get(Context context) {
        if (instance == null) {
            instance = new SqliteArenaRepository(new DbHelper(context.getApplicationContext()));
        }
        return instance;
    }
}
