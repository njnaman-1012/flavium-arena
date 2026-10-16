package in.flavium.arena.ui.events;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.ui.common.RowAdapter;
import in.flavium.arena.ui.common.Ui;
import in.flavium.arena.ui.matches.FixturesActivity;
import in.flavium.arena.auth.AccessControl;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.EventRound;
import in.flavium.arena.model.SportTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The heats and the final of one category in which many entrants compete at once:
 * a race, a relay, a swimming event, discus or shot put. (Sports where two sides meet
 * use FixturesActivity instead.)
 */
public class RoundsActivity extends BaseActivity {

    private RowAdapter adapter;
    private String categoryId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_rounds, "Heats and finals", true);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);

        adapter = new RowAdapter(this);
        ListView listView = findViewById(R.id.listView);
        listView.setEmptyView(findViewById(R.id.tvEmpty));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(this, RoundDetailActivity.class);
            intent.putExtra(EXTRA_ROUND_ID, adapter.getItem(position).id);
            startActivity(intent);
        });
        ((TextView) findViewById(R.id.tvEmptyTitle)).setText("Nothing scheduled yet");
        ((TextView) findViewById(R.id.tvEmptyText))
                .setText("Heats and finals appear here as soon as they are scheduled.");

        findViewById(R.id.btnAdd).setOnClickListener(v -> {
            Intent intent = new Intent(this, RoundEditActivity.class);
            intent.putExtra(EXTRA_CATEGORY_ID, categoryId);
            startActivity(intent);
        });
        findViewById(R.id.btnDrawFinal).setOnClickListener(v ->
                showDialog("Create the final from the heats? Everyone who qualified is entered, "
                                + "with the fastest in lane 1.", R.drawable.ic_check, false,
                        "Create final", "Cancel", () -> {
                            if (attempt(() -> repo.createFinalFromHeats(currentUser, categoryId))) {
                                toast("Final created");
                            }
                            showRows();
                        }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        showRows();
    }

    private void showRows() {
        Category category = repo.getCategory(categoryId);
        if (category == null) {
            finish();
            return;
        }
        setScreenTitle(category.name);

        // Only a race can be run in heats; everything else is one competition, the final.
        boolean heats = SportTemplate.ENGINE_TIMED.equals(category.engine)
                && category.ruleSet().isYes("heats");
        boolean hasHeat = false;
        boolean hasFinal = false;
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (EventRound r : repo.getRounds(categoryId)) {
            hasHeat = hasHeat || r.isHeat();
            hasFinal = hasFinal || !r.isHeat();
            rows.add(Ui.roundRow(r, false));
        }
        adapter.setRows(rows);

        ((TextView) findViewById(R.id.tvHeader)).setText(heats
                ? "This race is run in heats. The best of each heat go through to the final."
                : "Everyone competes in one final.");

        boolean canManage = AccessControl.canManageFixtures(userIn(category.sportId));
        Button btnAdd = findViewById(R.id.btnAdd);
        btnAdd.setText(heats ? "Add a heat or the final" : "Schedule the final");
        show(btnAdd, canManage && (heats || !hasFinal));
        show(findViewById(R.id.btnDrawFinal), canManage && heats && hasHeat && !hasFinal);
    }

    // ----- Downloads -----

    /** The results of the category as a file. */
    @Override
    protected String[] downloadNames() {
        return new String[]{"Results of every heat and final (CSV)"};
    }

    @Override
    protected Download downloadAt(int position) {
        return exports -> exports.categoryStandings(categoryId);
    }
}
