package in.flavium.arena.scoring;

import in.flavium.arena.model.Branch;
import in.flavium.arena.model.MedalRow;
import in.flavium.arena.model.MedalWin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rules of the medal table. Pure Java, covered by unit tests.
 *
 * A medal is only a medal: it carries no points. The table is ordered by the number of
 * gold medals, then silver, then bronze.
 */
public final class Medals {

    public static final String GOLD = "GOLD";
    public static final String SILVER = "SILVER";
    public static final String BRONZE = "BRONZE";

    private Medals() {
    }

    /**
     * How many medals a category awards, from its "Medals awarded" rule:
     * "Gold, Silver and Bronze" 3, "Gold and Silver" 2, "Gold only" 1, "No medals" 0.
     * A category without the rule (created before the rule existed) awards all three.
     */
    public static int countFor(String rule) {
        String text = rule == null ? "" : rule.toLowerCase(Locale.US);
        if (text.startsWith("no")) {
            return 0;
        }
        if (text.contains("bronze") || text.isEmpty()) {
            return 3;
        }
        return text.contains("silver") ? 2 : 1;
    }

    /** The medal for a finishing position, or null when that position gets none. */
    public static String forPosition(int position, int medalCount) {
        if (position < 1 || position > medalCount) {
            return null;
        }
        return position == 1 ? GOLD : position == 2 ? SILVER : position == 3 ? BRONZE : null;
    }

    public static String label(String medal) {
        return GOLD.equals(medal) ? "Gold" : SILVER.equals(medal) ? "Silver" : "Bronze";
    }

    /** Gold before silver before bronze. */
    public static int order(String medal) {
        return GOLD.equals(medal) ? 0 : SILVER.equals(medal) ? 1 : 2;
    }

    // ----- Knockout categories: which fixture decides which medal -----

    /** True for the fixture whose round is named "Final" (not "Semi-final" and so on). */
    public static boolean isFinalRound(String roundName) {
        String name = roundName == null ? "" : roundName.trim().toLowerCase(Locale.US);
        return name.equals("final") || name.equals("finals") || name.equals("the final")
                || name.equals("grand final");
    }

    /** True for the fixture that decides third place: "Third place", "Bronze match"... */
    public static boolean isThirdPlaceRound(String roundName) {
        String name = roundName == null ? "" : roundName.trim().toLowerCase(Locale.US);
        return name.contains("third") || name.contains("3rd") || name.contains("bronze");
    }

    // ----- The table -----

    /**
     * Counts the medals of every active branch and orders the branches: most gold first,
     * then most silver, then most bronze, then by name. Branches with the same three
     * counts share a rank. A medal whose winner has no active branch is not counted.
     */
    public static List<MedalRow> table(List<MedalWin> wins, List<Branch> branches) {
        Map<String, MedalRow> rows = new LinkedHashMap<>();
        for (Branch b : branches) {
            if (!b.active) {
                continue;
            }
            MedalRow row = new MedalRow();
            row.branchId = b.id;
            row.branchName = b.name;
            row.branchCode = b.code;
            rows.put(b.id, row);
        }
        for (MedalWin win : wins) {
            MedalRow row = win.branchId == null ? null : rows.get(win.branchId);
            if (row == null) {
                continue;
            }
            if (GOLD.equals(win.medal)) {
                row.gold++;
            } else if (SILVER.equals(win.medal)) {
                row.silver++;
            } else if (BRONZE.equals(win.medal)) {
                row.bronze++;
            }
        }

        List<MedalRow> result = new ArrayList<>(rows.values());
        Collections.sort(result, new Comparator<MedalRow>() {
            @Override
            public int compare(MedalRow x, MedalRow y) {
                int byMedals = compareMedals(x, y);
                return byMedals != 0 ? byMedals : x.branchName.compareToIgnoreCase(y.branchName);
            }
        });
        for (int i = 0; i < result.size(); i++) {
            boolean level = i > 0 && compareMedals(result.get(i - 1), result.get(i)) == 0;
            result.get(i).rank = level ? result.get(i - 1).rank : i + 1;
        }
        return result;
    }

    private static int compareMedals(MedalRow x, MedalRow y) {
        if (x.gold != y.gold) {
            return y.gold - x.gold;
        }
        if (x.silver != y.silver) {
            return y.silver - x.silver;
        }
        return y.bronze - x.bronze;
    }
}
