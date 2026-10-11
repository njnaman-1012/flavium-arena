package com.example.flaviumarena.model;

/**
 * The four roles an account can have. They are stored as text in the users table.
 * There is no viewer role: the public uses the app without an account.
 */
public final class Role {

    public static final String FACULTY = "FACULTY";
    public static final String CORE = "CORE";
    public static final String ASOO_CORE = "ASOO_CORE";
    public static final String CAPTAIN = "CAPTAIN";

    /** Highest authority first. The login screen shows them in this order. */
    public static final String[] ALL = {FACULTY, CORE, ASOO_CORE, CAPTAIN};
    public static final String[] LABELS = {"Faculty", "Core", "Asoo Core", "Branch Captain"};

    private Role() {
    }

    public static boolean isValid(String role) {
        for (String r : ALL) {
            if (r.equals(role)) {
                return true;
            }
        }
        return false;
    }

    /** Readable name of a role, for example "Asoo Core". A null role is the public. */
    public static String label(String role) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].equals(role)) {
                return LABELS[i];
            }
        }
        return "Public visitor";
    }
}
