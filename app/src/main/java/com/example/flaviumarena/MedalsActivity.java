package com.example.flaviumarena;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.flaviumarena.model.MedalRow;
import com.example.flaviumarena.model.MedalWin;
import com.example.flaviumarena.scoring.Medals;

import java.util.List;

/**
 * Medal table of the festival. Branches are ranked by gold medals, then silver, then
 * bronze; a medal carries no points. Below the table every medal is listed with the
 * category it was won in. Open to everyone, no login needed.
 */
public class MedalsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_medals, "Medal table", true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showTable();
        showWins();
    }

    private void showTable() {
        LinearLayout rows = findViewById(R.id.medalRows);
        rows.removeAllViews();
        for (MedalRow m : repo.getMedalTable()) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_medal_row, rows, false);
            bind(row, m);
            // The branch page shows which medals these are.
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, BranchPageActivity.class);
                intent.putExtra(EXTRA_BRANCH_ID, m.branchId);
                startActivity(intent);
            });
            rows.addView(row);
        }
    }

    /** Fills an item_medal_row view. Also used on the home screen. */
    public static void bind(View row, MedalRow m) {
        ((TextView) row.findViewById(R.id.tvMedalRank)).setText(String.valueOf(m.rank));
        ((TextView) row.findViewById(R.id.tvMedalBranch)).setText(m.branchName);
        ((TextView) row.findViewById(R.id.tvMedalTotal))
                .setText(m.total() + (m.total() == 1 ? " medal" : " medals"));
        ((TextView) row.findViewById(R.id.tvMedalGold)).setText(String.valueOf(m.gold));
        ((TextView) row.findViewById(R.id.tvMedalSilver)).setText(String.valueOf(m.silver));
        ((TextView) row.findViewById(R.id.tvMedalBronze)).setText(String.valueOf(m.bronze));
    }

    private void showWins() {
        LinearLayout list = findViewById(R.id.medalWins);
        list.removeAllViews();
        List<MedalWin> wins = repo.getMedals(null);
        show(findViewById(R.id.tvNoMedals), wins.isEmpty());
        for (MedalWin win : wins) {
            View card = LayoutInflater.from(this).inflate(R.layout.row_item, list, false);
            RowAdapter.bind(card, winRow(win));
            ((ViewGroup.MarginLayoutParams) card.getLayoutParams()).bottomMargin =
                    (int) (10 * getResources().getDisplayMetrics().density);
            card.setOnClickListener(v -> {
                Intent intent = new Intent(this, StandingsActivity.class);
                intent.putExtra(EXTRA_CATEGORY_ID, win.categoryId);
                startActivity(intent);
            });
            list.addView(card);
        }
    }

    /** List card for one medal: who won it, in which category, and which medal. */
    public static RowAdapter.Row winRow(MedalWin win) {
        String branch = win.branchName == null || win.branchName.isEmpty() || win.team
                ? "" : win.branchName + "\n";
        return new RowAdapter.Row(win.registrationId, win.name,
                branch + win.sportName + ", " + win.categoryName, Medals.label(win.medal),
                Ui.sportPicture(win.sportName));
    }
}
