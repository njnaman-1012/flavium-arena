package in.flavium.arena.model;

/**
 * The five roles an account can have. They are stored as text in the users table.
 * The stored value of the branch representative is still CAPTAIN; on screen the
 * role is called "Branch CR".
 * There is no viewer role: the public uses the app without an account.
 */
public final class Role {

    public static final String FACULTY = "FACULTY";
    public static final String CORE = "CORE";
    public static final String ASOO_CORE = "ASOO_CORE";
    /** Looks after the sports Faculty assigns to them, and nothing else. */
    public static final String COACH = "COACH";
    public static final String CAPTAIN = "CAPTAIN";

    /** Highest authority first. The login screen shows them in this order. */
    public static final String[] ALL = {FACULTY, CORE, COACH, ASOO_CORE, CAPTAIN};
    public static final String[] LABELS = {"Faculty", "Core", "Coach", "Asoo Core",
            "Branch CR"};

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
