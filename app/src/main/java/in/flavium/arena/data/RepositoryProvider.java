package in.flavium.arena.data;

import android.content.Context;

/**
 * Gives every screen the same repository object.
 * This is the single place to change when the app moves from SQLite to an online database.
 */
public final class RepositoryProvider {

    private static ArenaRepository instance;
    private static DbHelper helper;

    private RepositoryProvider() {
    }

    public static synchronized ArenaRepository get(Context context) {
        if (instance == null) {
            helper = new DbHelper(context.getApplicationContext());
            instance = new SqliteArenaRepository(helper);
        }
        return instance;
    }

    /**
     * The database itself, for the synchronisation engine, which copies rows between it
     * and the server. Screens do not use this: they go through the repository.
     */
    public static synchronized DbHelper database(Context context) {
        return helper(context);
    }

    /** The database behind the repository. Only for classes of this package. */
    static synchronized DbHelper helper(Context context) {
        get(context);
        return helper;
    }
}
