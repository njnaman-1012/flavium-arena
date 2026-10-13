package com.example.flaviumarena.ui.matches;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.ui.common.RowAdapter;
import com.example.flaviumarena.ui.common.SimpleTextWatcher;
import com.example.flaviumarena.ui.common.Ui;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixtures with their live scores and results. Opened either for one category
 * or, from the home screen, for the whole festival.
 */
public class FixturesActivity extends BaseActivity {

    // Position 0 is "all", then one position per status, then the two special filters.
    private static final int FILTER_ALL = 0;
    private static final int FILTER_FINAL = Match.STATUSES.length + 1;
    private static final int FILTER_MINE = Match.STATUSES.length + 2;

    private RowAdapter adapter;
    private EditText etSearch;
    private Spinner spFilter;
    private String categoryId; // null = every category
    private List<Match> matches = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_list, "Fixtures and scores", true);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);

        etSearch = findViewById(R.id.etSearch);
        etSearch.setVisibility(View.VISIBLE);
        etSearch.setHint("Search team, sport, venue or round");
        etSearch.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                showRows();
            }
        });

        List<String> filters = new ArrayList<>();
        filters.add("All matches");
        for (String label : Match.STATUS_LABELS) {
            filters.add(label);
        }
        filters.add("Final results");
        if (AccessControl.isAsooCore(currentUser)) {
            filters.add("Assigned to me");
        }
        spFilter = findViewById(R.id.spFilter);
        spFilter.setVisibility(View.VISIBLE);
        setSpinnerItems(spFilter, filters.toArray(new String[0]));
        if (getIntent().getBooleanExtra(EXTRA_ONLY_MINE, false)
                && AccessControl.isAsooCore(currentUser)) {
            spFilter.setSelection(FILTER_MINE);
        }
        onSpinnerChanged(spFilter, this::showRows);

        adapter = new RowAdapter(this);
        ListView listView = findViewById(R.id.listView);
        listView.setEmptyView(findViewById(R.id.tvEmpty));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(this, MatchDetailActivity.class);
            intent.putExtra(EXTRA_MATCH_ID, adapter.getItem(position).id);
            startActivity(intent);
        });

        Button btnAdd = findViewById(R.id.btnAdd);
        btnAdd.setText("Add fixture");
        btnAdd.setOnClickListener(v -> {
            Intent intent = new Intent(this, FixtureEditActivity.class);
            intent.putExtra(EXTRA_CATEGORY_ID, categoryId);
            startActivity(intent);
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
        // A fixture belongs to a category, so "Add" is offered only inside one.
        Category shown = categoryId == null ? null : repo.getCategory(categoryId);
        show(findViewById(R.id.btnAdd), shown != null
                && AccessControl.canManageFixtures(userIn(shown.sportId)));
        matches = repo.getMatches(categoryId);
        showRows();
    }

    private void showRows() {
        String query = textOf(etSearch).toLowerCase();
        int filter = spFilter.getSelectedItemPosition();

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Match m : matches) {
            if (!matchesFilter(m, filter)) {
                continue;
            }
            String searchable = (m.teamAName + " " + m.teamBName + " " + m.sportName + " "
                    + m.categoryName + " " + m.venue + " " + m.round).toLowerCase();
            if (!searchable.contains(query)) {
                continue;
            }
            rows.add(Ui.matchRow(m));
        }
        adapter.setRows(rows);
    }

    private boolean matchesFilter(Match m, int filter) {
        if (filter == FILTER_ALL) {
            return true;
        }
        if (filter == FILTER_FINAL) {
            return m.isFinal;
        }
        if (filter == FILTER_MINE) {
            return currentUser != null && currentUser.id.equals(m.assignedTo);
        }
        return Match.STATUSES[filter - 1].equals(m.status);
    }

    // ----- Downloads -----

    /** The fixtures of this category, or of the whole festival, as a file. */
    @Override
    protected String[] downloadNames() {
        return new String[]{"Fixtures and scores (CSV)"};
    }

    @Override
    protected Download downloadAt(int position) {
        return exports -> exports.fixtures(categoryId);
    }
}
