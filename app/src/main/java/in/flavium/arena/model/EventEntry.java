package in.flavium.arena.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One entrant in a heat, a final or a throwing competition, with their result.
 *
 *   Timed events (races, relays, swimming): lane, timeMs and position.
 *   Measured events (discus, shot put):     attempts, bestCm and position.
 */
public class EventEntry {

    /** Result statuses other than a normal result. */
    public static final String STATUS_OK = "";
    public static final String STATUS_DQ = "DQ";    // disqualified
    public static final String STATUS_DNS = "DNS";  // did not start
    public static final String STATUS_DNF = "DNF";  // did not finish
    public static final String[] STATUSES = {STATUS_OK, STATUS_DQ, STATUS_DNS, STATUS_DNF};
    public static final String[] STATUS_LABELS = {"Result counts", "Disqualified",
            "Did not start", "Did not finish"};

    /** One throw. */
    public static class Attempt {
        public int number;
        /** Measured distance in centimetres, or null for a foul. */
        public Integer distanceCm;
        public boolean foul;
    }

    public String id;
    public String roundId;
    public String registrationId;
    /** Lane in a race, or throwing order in a field event. 0 = not set. */
    public int lane;
    /** Finishing time in thousandths of a second, or null. */
    public Integer timeMs;
    public String resultStatus = STATUS_OK;
    /** Place in this heat or final. Null when the entrant has no valid result. */
    public Integer position;
    /** True when this result earns a place in the final. */
    public boolean qualified;
    /** Best valid throw in centimetres, or null. */
    public Integer bestCm;
    public List<Attempt> attempts = new ArrayList<>();

    // Filled in by the repository for display
    public String name;
    public String branchId;
    public String branchName;
    public String branchCode;
    public int year;
    public String logoPath;
    public boolean team;

    public boolean hasNormalStatus() {
        return resultStatus == null || resultStatus.isEmpty();
    }

    public Attempt attempt(int number) {
        for (Attempt a : attempts) {
            if (a.number == number) {
                return a;
            }
        }
        return null;
    }
}
