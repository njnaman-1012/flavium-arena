package in.flavium.arena.data;

import android.database.sqlite.SQLiteDatabase;

/**
 * Release builds contain no development accounts and no sample data.
 * The real class with that content is in app/src/debug and is not compiled here.
 */
public final class DevData {

    private DevData() {
    }

    public static void insert(SQLiteDatabase db) {
        // Nothing to add in a release build.
    }
}
