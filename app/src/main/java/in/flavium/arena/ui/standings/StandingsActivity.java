package in.flavium.arena.ui.standings;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import in.flavium.arena.scoring.EventSummary;
import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.ui.common.RowAdapter;
import in.flavium.arena.ui.common.Ui;
import in.flavium.arena.ui.events.RoundDetailActivity;
import in.flavium.arena.ui.matches.MatchDetailActivity;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.EventEntry;
import in.flavium.arena.model.EventRound;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.MedalWin;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.SportTemplate;
import in.flavium.arena.model.StandingRow;
import in.flavium.arena.scoring.Medals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standings of one category. What "standings" means depends on how it is played:
 *
 *   League          a points table with the columns that suit the sport
 *   Knockout        the fixtures round by round, with who went through
 *   Race or throw   the placings of the final
 *
 * Everything is worked out from the results each time the screen opens; nothing here is
 * stored. The medals of the category are shown above. The list at the top switches to
 * another category.
 */
public class StandingsActivity extends BaseActivity {

    private String categoryId;
    private final List<String> categoryIds = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_standings, "Standings", true);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);

        // Every category of the festival, so the visitor can move between tables.
        List<String> names = new ArrayList<>();
        for (Sport sport : repo.getSports()) {
            for (Category c : repo.getCategories(sport.id)) {
                names.add(sport.name + ": " + c.name);
                categoryIds.add(c.id);
            }
        }
        Spinner spCategory = findViewById(R.id.spCategory);
        setSpinnerItems(spCategory, names.toArray(new String[0]));
        spCategory.setSelection(Math.max(0, categoryIds.indexOf(categoryId)));
        onSpinnerChanged(spCategory, () -> {
            categoryId = categoryIds.get(spCategory.getSelectedItemPosition());
            showCategory();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        showCategory();
    }

    private void showCategory() {
        Category category = repo.getCategory(categoryId);
        if (category == null) {
            finish();
            return;
        }
        LinearLayout rows = findViewById(R.id.standingRows);
        rows.removeAllViews();
        show(findViewById(R.id.tvStandingsEmpty), false);

        boolean manyEntrants = SportTemplate.ENGINE_TIMED.equals(category.engine)
                || SportTemplate.ENGINE_MEASURED.equals(category.engine);
        if (manyEntrants) {
            showFinalPlacings(category, rows);
        } else if (category.isKnockout()) {
            showKnockout(category, rows);
        } else {
            showTable(category, rows);
        }
        showMedals(category, manyEntrants);
    }

    private void setNote(String text) {
        ((TextView) findViewById(R.id.tvStandingsNote)).setText(text);
    }

    private void showEmpty(String text) {
        TextView tvEmpty = findViewById(R.id.tvStandingsEmpty);
        tvEmpty.setText(text);
        show(tvEmpty, true);
    }

    // =====================================================================
    // Medals of the category
    // =====================================================================

    private void showMedals(Category category, boolean manyEntrants) {
        TextView tvMedals = findViewById(R.id.tvCategoryMedals);
        if (Medals.countFor(category.ruleSet().get("medals", "")) == 0) {
            tvMedals.setText("This category awards no medals.");
            return;
        }
        List<MedalWin> wins = repo.getMedals(categoryId);
        if (wins.isEmpty()) {
            // Say what has to happen before the medals are known.
            String when;
            if (manyEntrants) {
                when = "when the results of the final are made official";
            } else if (category.isKnockout()) {
                when = "by the fixture named \"Final\"; bronze goes to the winner of a "
                        + "\"Third place\" fixture";
            } else {
                when = "when every fixture of the league is final";
            }
            tvMedals.setText("Medals are decided " + when + ".");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (MedalWin win : wins) {
            text.append(text.length() > 0 ? "\n" : "").append(Medals.label(win.medal))
                    .append(": ").append(win.name);
            // A team is named after its branch already; an individual is not.
            if (!win.team && win.branchCode != null && !win.branchCode.isEmpty()) {
                text.append(" (").append(win.branchCode).append(")");
            }
        }
        tvMedals.setText(text.toString());
    }

    // =====================================================================
    // League: points table
    // =====================================================================

    /**
     * Headings of the six number columns for a scoring engine. An empty heading hides
     * the column: badminton has no draws, chess has no score difference, and so on.
     */
    private static String[] headingsFor(String engine) {
        if (SportTemplate.ENGINE_SETS.equals(engine)) {
            return new String[]{"P", "W", "L", "", "+/-", "Pts"};
        }
        if (SportTemplate.ENGINE_CRICKET.equals(engine)) {
            return new String[]{"P", "W", "L", "T", "", "Pts"};
        }
        if (SportTemplate.ENGINE_RESULT.equals(engine)) {
            return new String[]{"P", "W", "D", "L", "", "Pts"};
        }
        return new String[]{"P", "W", "D", "L", "+/-", "Pts"};
    }

    private static String[] valuesFor(String engine, StandingRow s) {
        String difference = (s.difference() > 0 ? "+" : "") + s.difference();
        String points = String.valueOf(s.points);
        if (SportTemplate.ENGINE_SETS.equals(engine)) {
            return new String[]{"" + s.played, "" + s.won, "" + s.lost, "", difference, points};
        }
        if (SportTemplate.ENGINE_CRICKET.equals(engine)) {
            return new String[]{"" + s.played, "" + s.won, "" + s.lost, "" + s.drawn, "", points};
        }
        return new String[]{"" + s.played, "" + s.won, "" + s.drawn, "" + s.lost,
                SportTemplate.ENGINE_RESULT.equals(engine) ? "" : difference, points};
    }

    private void showTable(Category category, LinearLayout rows) {
        String engine = category.engine;
        String legend = "P played, W won, ";
        if (SportTemplate.ENGINE_SETS.equals(engine)) {
            legend += "L lost, +/- sets won minus sets lost";
        } else if (SportTemplate.ENGINE_CRICKET.equals(engine)) {
            legend += "L lost, T tied";
        } else if (SportTemplate.ENGINE_RESULT.equals(engine)) {
            legend += "D drawn, L lost";
        } else {
            legend += "D drawn, L lost, +/- scored minus conceded";
        }
        setNote("League table. Only finalised results count.\n" + legend + ", Pts league "
                + "points (win " + category.ptsWin + ", draw " + category.ptsDraw + ", loss "
                + category.ptsLoss + ").");

        List<StandingRow> table = repo.getStandings(categoryId);
        if (table.isEmpty()) {
            showEmpty("No approved teams or participants yet.");
            return;
        }
        addTableLine(rows, "", "", headingsFor(engine), headingsFor(engine), true);
        int place = 1;
        for (StandingRow s : table) {
            addTableLine(rows, String.valueOf(place),
                    s.detail.isEmpty() ? s.name : s.name + "\n" + s.detail,
                    headingsFor(engine), valuesFor(engine, s), false);
            place++;
        }
    }

    private void addTableLine(LinearLayout rows, String place, String name, String[] headings,
                              String[] values, boolean heading) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_standing_row, rows, false);
        ((TextView) line.findViewById(R.id.tvStandRank)).setText(place);
        ((TextView) line.findViewById(R.id.tvStandName)).setText(name);
        int[] ids = {R.id.tvStand1, R.id.tvStand2, R.id.tvStand3, R.id.tvStand4, R.id.tvStand5,
                R.id.tvStand6};
        for (int i = 0; i < ids.length; i++) {
            TextView cell = line.findViewById(ids[i]);
            cell.setText(values[i]);
            show(cell, !headings[i].isEmpty());
            if (heading) {
                cell.setTypeface(null, Typeface.BOLD);
                cell.setTextColor(getColor(R.color.text_secondary));
            }
        }
        if (heading) {
            // The heading line has no card around it.
            line.setBackground(null);
            line.setPadding(line.getPaddingLeft(), 0, line.getPaddingRight(), 0);
        }
        rows.addView(line);
    }

    // =====================================================================
    // Knockout: round by round
    // =====================================================================

    private void showKnockout(Category category, LinearLayout rows) {
        setNote("Knockout. The winner of each fixture goes through to the next round.");
        // Fixtures grouped by the name of their round, in the order the rounds are played.
        Map<String, List<Match>> rounds = new LinkedHashMap<>();
        for (Match m : repo.getMatches(categoryId)) {
            String round = m.round.trim().isEmpty() ? "Fixtures" : m.round.trim();
            if (!rounds.containsKey(round)) {
                rounds.put(round, new ArrayList<>());
            }
            rounds.get(round).add(m);
        }
        if (rounds.isEmpty()) {
            showEmpty("No fixtures have been scheduled yet.");
            return;
        }
        for (Map.Entry<String, List<Match>> round : rounds.entrySet()) {
            addSectionTitle(rows, round.getKey());
            for (Match m : round.getValue()) {
                int winner = m.isFinal && m.hasScore() ? m.winnerSide() : 0;
                String line;
                if (winner != 0) {
                    line = "Went through: " + (winner == 1 ? m.sideAShort() : m.sideBShort());
                } else if (m.isFinal) {
                    line = "Level: no winner recorded";
                } else {
                    line = m.date + " " + m.time + (m.venue.isEmpty() ? "" : ", " + m.venue);
                }
                RowAdapter.Row row = new RowAdapter.Row(m.id,
                        m.sideAText() + " vs " + m.sideBText(), line,
                        m.statusLabel() + (m.hasScore() ? "\n" + m.scoreText() : ""),
                        Ui.sportPicture(m.sportName));
                Ui.addCard(rows, row, () -> {
                    Intent intent = new Intent(this, MatchDetailActivity.class);
                    intent.putExtra(EXTRA_MATCH_ID, m.id);
                    startActivity(intent);
                });
            }
        }
    }

    // =====================================================================
    // Race or throw: placings of the final
    // =====================================================================

    private void showFinalPlacings(Category category, LinearLayout rows) {
        EventRound finalRound = null;
        for (EventRound r : repo.getRounds(categoryId)) {
            if (!r.isHeat()) {
                finalRound = r;
            }
        }
        if (finalRound == null) {
            setNote("Placings of the final.");
            showEmpty("The final has not been scheduled yet. Heats are listed under "
                    + "\"Heats and final\" on the category page.");
            return;
        }
        setNote(finalRound.isFinal ? "Official placings of the final."
                : "Placings of the final. They are provisional until the results are made "
                + "official (status: " + finalRound.status + ").");

        String roundId = finalRound.id;
        boolean measured = finalRound.isMeasured();
        for (EventEntry e : repo.getEntries(roundId)) {
            String result = EventSummary.badge(measured, e);
            RowAdapter.Row row = new RowAdapter.Row(e.id, e.name,
                    e.team ? "" : Registration.branchLine(e.branchName, e.year), result);
            row.circleText = e.position == null ? "-" : String.valueOf(e.position);
            row.imagePath = e.logoPath;
            Ui.addCard(rows, row, () -> {
                Intent intent = new Intent(this, RoundDetailActivity.class);
                intent.putExtra(EXTRA_ROUND_ID, roundId);
                startActivity(intent);
            });
        }
        if (rows.getChildCount() == 0) {
            showEmpty("Nobody has been entered in the final yet.");
        }
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private void addSectionTitle(LinearLayout rows, String text) {
        TextView title = new TextView(this);
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        title.setText(text);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        title.setPadding(0, gap, 0, gap);
        rows.addView(title);
    }

    // ----- Downloads -----

    /** What this screen shows for the chosen category, as a file. */
    @Override
    protected String[] downloadNames() {
        return new String[]{"Standings and medals of this category (CSV)"};
    }

    @Override
    protected Download downloadAt(int position) {
        return exports -> exports.categoryStandings(categoryId);
    }
}
