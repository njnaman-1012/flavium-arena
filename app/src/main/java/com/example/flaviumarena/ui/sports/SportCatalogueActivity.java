package com.example.flaviumarena.ui.sports;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.ui.common.RowAdapter;
import com.example.flaviumarena.ui.common.SimpleTextWatcher;
import com.example.flaviumarena.ui.common.Ui;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The catalogue an organiser picks from when adding a sport.
 *
 * Each card is a template read from the database. Picking one adds the sport with that
 * template's name and settings, so nothing has to be typed again. "Custom sport" is for
 * anything that is not listed: the organiser names it and chooses how it is scored.
 */
public class SportCatalogueActivity extends BaseActivity {

    private RowAdapter adapter;
    private EditText etSearch;
    private List<SportTemplate> templates = new ArrayList<>();
    private final List<SportTemplate> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_list, "Add a sport", true);

        TextView tvHeader = findViewById(R.id.tvHeader);
        show(tvHeader, true);
        tvHeader.setText("Choose a sport. Its usual settings are loaded for you and can be "
                + "changed for each category.");

        etSearch = findViewById(R.id.etSearch);
        etSearch.setVisibility(View.VISIBLE);
        etSearch.setHint("Search the catalogue");
        etSearch.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                showRows();
            }
        });

        adapter = new RowAdapter(this);
        ListView listView = findViewById(R.id.listView);
        listView.setEmptyView(findViewById(R.id.tvEmpty));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                choose(shown.get(position)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        if (!AccessControl.canManageSports(currentUser)) {
            toast("Only Faculty and Cores can add sports.");
            finish();
            return;
        }
        templates = repo.getTemplates();
        showRows();
    }

    private void showRows() {
        // Sports that are already in the festival are marked, to avoid duplicates.
        Set<String> added = new HashSet<>();
        for (Sport sport : repo.getSports()) {
            added.add(sport.name.toLowerCase());
        }

        String query = textOf(etSearch).toLowerCase();
        shown.clear();
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (SportTemplate t : templates) {
            if (!(t.name + " " + t.description).toLowerCase().contains(query)) {
                continue;
            }
            shown.add(t);
            boolean already = !t.isCustom() && added.contains(t.name.toLowerCase());
            rows.add(new RowAdapter.Row(t.key, t.name, t.description, already ? "Added" : "",
                    t.isCustom() ? R.drawable.ill_medal : Ui.sportPicture(t.name)));
        }
        adapter.setRows(rows);
    }

    private void choose(SportTemplate template) {
        if (template.isCustom()) {
            askCustomName(template);
            return;
        }
        showDialog("Add " + template.name + " to the festival?", R.drawable.ic_plus, false,
                "Add " + template.name, "Cancel", () -> add(template, null, null));
    }

    /** A custom sport needs a name; how it is scored is chosen with its first category. */
    private void askCustomName(SportTemplate template) {
        View form = getLayoutInflater().inflate(R.layout.dialog_sport, null);
        EditText etName = form.findViewById(R.id.etSportName);
        EditText etDescription = form.findViewById(R.id.etSportDescription);
        etName.setHint("For example: Kabaddi");
        new AlertDialog.Builder(this)
                .setTitle("Custom sport")
                .setView(form)
                .setPositiveButton("Add", (dialog, which) ->
                        add(template, textOf(etName), textOf(etDescription)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void add(SportTemplate template, String name, String description) {
        final String[] sportId = new String[1];
        if (attempt(() -> sportId[0] = repo.addSportFromTemplate(currentUser, template.key,
                name, description))) {
            toast("Sport added. Now add its categories.");
            Intent intent = new Intent(this, CategoriesActivity.class);
            intent.putExtra(EXTRA_SPORT_ID, sportId[0]);
            startActivity(intent);
            finish();
        }
    }
}
