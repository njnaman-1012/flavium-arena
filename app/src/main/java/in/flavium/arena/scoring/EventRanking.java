package in.flavium.arena.scoring;

import in.flavium.arena.model.EventEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Works out positions in races and throwing events. Pure Java, covered by unit tests.
 *
 * Entrants without a valid result (no time or distance yet, disqualified, did not
 * start, did not finish) get no position. Equal results share a position, and the next
 * position is skipped, as in "1, 2, 2, 4".
 */
public final class EventRanking {

    private EventRanking() {
    }

    // ----- Races -----

    /**
     * Fastest time first. The first "qualifyCount" positions are marked as qualified;
     * pass 0 when nobody qualifies from this race (a final, or an event without heats).
     */
    public static void rankTimed(List<EventEntry> entries, int qualifyCount) {
        List<EventEntry> finishers = new ArrayList<>();
        for (EventEntry e : entries) {
            e.position = null;
            e.qualified = false;
            if (e.hasNormalStatus() && e.timeMs != null) {
                finishers.add(e);
            }
        }
        Collections.sort(finishers, new Comparator<EventEntry>() {
            @Override
            public int compare(EventEntry x, EventEntry y) {
                return x.timeMs - y.timeMs;
            }
        });
        for (int i = 0; i < finishers.size(); i++) {
            EventEntry e = finishers.get(i);
            boolean tied = i > 0 && finishers.get(i - 1).timeMs.equals(e.timeMs);
            e.position = tied ? finishers.get(i - 1).position : Integer.valueOf(i + 1);
            e.qualified = e.position <= qualifyCount;
        }
    }

    /**
     * The fastest finishers among those who did NOT qualify by position, taken across
     * all heats ("fastest losers"). Call rankTimed on each heat first.
     */
    public static List<EventEntry> fastestNonQualifiers(List<EventEntry> allHeatEntries,
                                                        int count) {
        List<EventEntry> candidates = new ArrayList<>();
        for (EventEntry e : allHeatEntries) {
            if (!e.qualified && e.hasNormalStatus() && e.timeMs != null) {
                candidates.add(e);
            }
        }
        Collections.sort(candidates, new Comparator<EventEntry>() {
            @Override
            public int compare(EventEntry x, EventEntry y) {
                return x.timeMs - y.timeMs;
            }
        });
        return new ArrayList<>(candidates.subList(0, Math.min(count, candidates.size())));
    }

    // ----- Throws -----

    /** Valid distances of an entrant, longest first, using attempts up to "upToAttempt". */
    private static List<Integer> validDistances(EventEntry e, int upToAttempt) {
        List<Integer> distances = new ArrayList<>();
        for (EventEntry.Attempt a : e.attempts) {
            if (a.number <= upToAttempt && !a.foul && a.distanceCm != null) {
                distances.add(a.distanceCm);
            }
        }
        Collections.sort(distances, Collections.<Integer>reverseOrder());
        return distances;
    }

    /**
     * Compares two entrants by best throw; when "secondBest" is on, equal best throws
     * are separated by the second-best throw, then the third, and so on.
     */
    private static int compareThrows(List<Integer> x, List<Integer> y, boolean secondBest) {
        int depth = secondBest ? Math.max(x.size(), y.size()) : 1;
        for (int i = 0; i < depth; i++) {
            int a = i < x.size() ? x.get(i) : 0;
            int b = i < y.size() ? y.get(i) : 0;
            if (a != b) {
                return b - a; // the longer throw comes first
            }
        }
        return 0;
    }

    /**
     * Longest valid throw first. Fills in bestCm and position for every entrant.
     * A foul never counts; an entrant with only fouls has no position.
     */
    public static void rankMeasured(List<EventEntry> entries, boolean secondBestBreaksTies) {
        rankThrows(entries, Integer.MAX_VALUE, secondBestBreaksTies, true);
    }

    /**
     * The entrants who may take the extra attempts: the best "finalists" after the
     * first "attempts" throws. Entrants level with the last of them are included.
     */
    public static List<EventEntry> finalists(List<EventEntry> entries, int attempts,
                                             int finalists, boolean secondBestBreaksTies) {
        // Rank on copies of the positions so the stored positions are not disturbed.
        List<Integer> saved = new ArrayList<>();
        for (EventEntry e : entries) {
            saved.add(e.position);
        }
        rankThrows(entries, attempts, secondBestBreaksTies, false);
        List<EventEntry> result = new ArrayList<>();
        for (EventEntry e : entries) {
            if (e.position != null && e.position <= finalists) {
                result.add(e);
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).position = saved.get(i);
        }
        return result;
    }

    private static void rankThrows(List<EventEntry> entries, final int upToAttempt,
                                   final boolean secondBest, boolean storeBest) {
        List<EventEntry> ranked = new ArrayList<>();
        for (EventEntry e : entries) {
            e.position = null;
            List<Integer> distances = validDistances(e, upToAttempt);
            if (storeBest) {
                e.bestCm = distances.isEmpty() ? null : distances.get(0);
            }
            if (e.hasNormalStatus() && !distances.isEmpty()) {
                ranked.add(e);
            }
        }
        Collections.sort(ranked, new Comparator<EventEntry>() {
            @Override
            public int compare(EventEntry x, EventEntry y) {
                return compareThrows(validDistances(x, upToAttempt),
                        validDistances(y, upToAttempt), secondBest);
            }
        });
        for (int i = 0; i < ranked.size(); i++) {
            EventEntry e = ranked.get(i);
            boolean tied = i > 0 && compareThrows(validDistances(ranked.get(i - 1), upToAttempt),
                    validDistances(e, upToAttempt), secondBest) == 0;
            e.position = tied ? ranked.get(i - 1).position : Integer.valueOf(i + 1);
        }
    }
}
