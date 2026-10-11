package com.example.flaviumarena.auth;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.model.User;

/**
 * Remembers who is logged in. Only the user id is saved on the device.
 * The role is read from the database every time, so editing the saved
 * preferences cannot give anyone a higher role.
 */
public final class Session {

    private static final String PREFS = "flavium_session";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_ONBOARDING_SEEN = "onboarding_seen";

    private Session() {
    }

    public static void login(Context context, String userId) {
        prefs(context).edit().putString(KEY_USER_ID, userId).apply();
    }

    public static void logout(Context context) {
        prefs(context).edit().remove(KEY_USER_ID).apply();
    }

    /** Returns the logged-in user, or null when nobody is logged in (guest Viewer). */
    public static User currentUser(Context context, ArenaRepository repo) {
        String id = prefs(context).getString(KEY_USER_ID, null);
        if (id == null) {
            return null;
        }
        User user = repo.getUser(id);
        // A removed or deactivated account is logged out straight away.
        if (user == null || !user.active) {
            logout(context);
            return null;
        }
        return user;
    }

    /** True once the welcome pages have been shown, so they appear only on first launch. */
    public static boolean hasSeenOnboarding(Context context) {
        return prefs(context).getBoolean(KEY_ONBOARDING_SEEN, false);
    }

    public static void setOnboardingSeen(Context context, boolean seen) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_SEEN, seen).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
