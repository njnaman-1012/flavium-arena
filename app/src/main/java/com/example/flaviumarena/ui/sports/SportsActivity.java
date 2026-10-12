package com.example.flaviumarena.ui.sports;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
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

import java.util.ArrayList;
import java.util.List;

/** List of sports. Everyone can browse; a Core can also add, edit and delete. */
public class SportsActivity extends BaseActivity {

    private RowAdapter adapter;
    private EditText etSearch;
    private List<Sport> sports = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_list, "Sports", true);

        etSearch = findViewById(R.id.etSearch);
        etSearch.setVisibility(View.VISIBLE);
        etSearch.setHint("Search sports");
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
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(this, CategoriesActivity.class);
            intent.putExtra(EXTRA_SPORT_ID, adapter.getItem(position).id);
            startActivity(intent);
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!AccessControl.canManageSports(currentUser)) {
                return false;
            }
            showOptions(findSport(adapter.getItem(position).id));
            return true;
        });

        Button btnAdd = findViewById(R.id.btnAdd);
        btnAdd.setText("Add sport");
        // New sports are picked from the catalogue (or added there as a custom sport).
        btnAdd.setOnClickListener(v ->
                startActivity(new Intent(this, SportCatalogueActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean canManage = AccessControl.canManageSports(currentUser);
        TextView tvHeader = findViewById(R.id.tvHeader);
        tvHeader.setText("Tap a sport to see its categories. Long-press to edit or delete.");
        show(tvHeader, canManage);
        show(findViewById(R.id.btnAdd), canManage);
        load();
    }

    private void load() {
        sports = repo.getSports();
        showRows();
    }

    private void showRows() {
        String query = textOf(etSearch).toLowerCase();
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Sport sport : sports) {
            if (sport.name.toLowerCase().contains(query)) {
                int categories = repo.getCategories(sport.id).size();
                rows.add(new RowAdapter.Row(sport.id, sport.name, sport.description,
                        categories + (categories == 1 ? " category" : " categories"),
                        Ui.sportPicture(sport.name)));
            }
        }
        adapter.setRows(rows);
    }

    private Sport findSport(String id) {
        for (Sport sport : sports) {
            if (sport.id.equals(id)) {
                return sport;
            }
        }
        return null;
    }

    private void showOptions(Sport sport) {
        if (sport == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(sport.name)
                .setItems(new String[]{"Rename or describe", "Delete"}, (dialog, which) -> {
                    if (which == 0) {
                        showSportDialog(sport);
                    } else {
                        confirm("Delete " + sport.name + "?", () -> {
                            attempt(() -> repo.deleteSport(currentUser, sport.id));
                            load();
                        });
                    }
                })
                .show();
    }

    /** Changes the name or description of a sport that has already been added. */
    private void showSportDialog(Sport existing) {
        View form = getLayoutInflater().inflate(R.layout.dialog_sport, null);
        EditText etName = form.findViewById(R.id.etSportName);
        EditText etDescription = form.findViewById(R.id.etSportDescription);
        if (existing != null) {
            etName.setText(existing.name);
            etDescription.setText(existing.description);
        }
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add sport" : "Edit sport")
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    Sport sport = new Sport();
                    sport.id = existing == null ? null : existing.id;
                    sport.name = textOf(etName);
                    sport.description = textOf(etDescription);
                    attempt(() -> repo.saveSport(currentUser, sport));
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
