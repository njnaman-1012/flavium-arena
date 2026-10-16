package in.flavium.arena.scoring;

import in.flavium.arena.model.Category;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.StandingRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the points table of a category. The table is never stored: it is worked
 * out from the finalised matches every time, so it cannot disagree with the results.
 */
public final class StandingsCalculator {

    private StandingsCalculator() {
    }

    public static List<StandingRow> calculate(Category category, List<Registration> teams,
                                              List<Match> matches) {
        // One row per approved team, all starting at zero.
        Map<String, StandingRow> rows = new LinkedHashMap<>();
        for (Registration team : teams) {
            if (!team.isApproved()) {
                continue;
            }
            StandingRow row = new StandingRow();
            row.registrationId = team.id;
            row.name = team.name;
            // A team is named after its branch and year; an individual is not.
            row.detail = category.isTeam() ? "" : team.branchLine();
            rows.put(team.id, row);
        }

        // Only results a Core has finalised count.
        for (Match match : matches) {
            if (!match.isFinal || !match.hasScore()) {
                continue;
            }
            StandingRow a = rows.get(match.teamAId);
            StandingRow b = rows.get(match.teamBId);
            if (a == null || b == null) {
                continue;
            }
            a.played++;
            b.played++;
            a.scored += match.scoreA;
            a.conceded += match.scoreB;
            b.scored += match.scoreB;
            b.conceded += match.scoreA;

            if (match.scoreA > match.scoreB) {
                a.won++;
                b.lost++;
                a.points += category.ptsWin;
                b.points += category.ptsLoss;
            } else if (match.scoreA < match.scoreB) {
                b.won++;
                a.lost++;
                b.points += category.ptsWin;
                a.points += category.ptsLoss;
            } else {
                a.drawn++;
                b.drawn++;
                a.points += category.ptsDraw;
                b.points += category.ptsDraw;
            }
        }

        // Order: points, then score difference, then score for, then name.
        List<StandingRow> result = new ArrayList<>(rows.values());
        Collections.sort(result, new Comparator<StandingRow>() {
            @Override
            public int compare(StandingRow x, StandingRow y) {
                if (x.points != y.points) {
                    return y.points - x.points;
                }
                if (x.difference() != y.difference()) {
                    return y.difference() - x.difference();
                }
                if (x.scored != y.scored) {
                    return y.scored - x.scored;
                }
                return x.name.compareToIgnoreCase(y.name);
            }
        });
        return result;
    }
}
