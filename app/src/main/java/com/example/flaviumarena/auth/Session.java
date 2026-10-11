package com.example.flaviumarena.auth;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.SqliteArenaRepository;
import com.example.flaviumarena.model.User;

/**
 * Remembers who is logged in on this device. Only the id of the login session is saved:
 * a long random value. Who that is, their role and whether the session is still live
 * are read from the database every time, so editing the saved preferences cannot give
 * anyone an account or a higher role, and a session that was signed out stops working.
 */
public final class Session {

    private static final String PREFS = "flavium_session";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_ONBOARDING_SEEN = "onboarding_seen";

    /** Set when a saved session turned out to have ended; shown once by the next screen. */
    private static String endMessage;

    private Session() {
    }

    public static void login(Context context, User user) {
        prefs(context).edit().putString(KEY_SESSION_ID, user.sessionId).apply();
    }

    /** Ends the session in the database and forgets it on this device. */
    public static void logout(Context context, ArenaRepository repo, User user) {
        repo.logout(user);
        prefs(context).edit().remove(KEY_SESSION_ID).apply();
    }

    /** Returns the logged-in user, or null when nobody is logged in (the public). */
    public static User currentUser(Context context, ArenaRepository repo) {
        String sessionId = prefs(context).getString(KEY_SESSION_ID, null);
        if (sessionId == null) {
            return null;
        }
        User user = repo.resumeSession(sessionId);
        if (user == null) {
            // The session ended somewhere else: forget it and say why, once.
            endMessage = messageFor(repo.getSessionEndReason(sessionId));
            prefs(context).edit().remove(KEY_SESSION_ID).apply();
        }
        return user;
    }

    /** The reason the last session ended, or null. Asking clears it. */
    public static String takeEndMessage() {
        String message = endMessage;
        endMessage = null;
        return message;
    }

    private static String messageFor(String reason) {
        if (SqliteArenaRepository.END_TAKEN_OVER.equals(reason)) {
            return "You have been logged out because this account logged in somewhere else.";
        }
        if (SqliteArenaRepository.END_EXPIRED.equals(reason)) {
            return "Your session expired because it was not used for a long time. "
                    + "Log in again.";
        }
        if (SqliteArenaRepository.END_PASSWORD_RESET.equals(reason)) {
            return "You have been logged out because the password of this account was "
                    + "reset. Log in with the new password.";
        }
        return "You have been logged out. Log in again to continue.";
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
