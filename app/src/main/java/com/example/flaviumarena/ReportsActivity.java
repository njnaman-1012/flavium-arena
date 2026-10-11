package com.example.flaviumarena;

import android.os.Bundle;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports of the whole festival in one place. Everyone can open this screen; each file
 * contains only what the person who downloads it may see, so a member of the public
 * gets approved entries without phone numbers, and an organiser gets the full records.
 */
public class ReportsActivity extends BaseActivity {

    private static final String[] TITLES = {"Festival report", "Medal table",
            "Branch list", "All registrations", "All fixtures and scores"};
    private static final String[] TEXTS = {
            "Medal table, medals won and final results, ready to print",
            "Gold, silver, bronze and total for every branch",
            "Branches with their registrations and medal counts",
            "Every team and participant, by branch, sport and category",
            "The schedule of the festival with scores and winners"};
    private static final String[] KINDS = {"PDF", "CSV", "CSV", "CSV", "CSV"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_list, "Reports and downloads", true);

        TextView tvHeader = findViewById(R.id.tvHeader);
        show(tvHeader, true);
        tvHeader.setText("Tap a report and choose where to save it. CSV files open in Excel "
                + "or Google Sheets.");

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) {
            rows.add(new RowAdapter.Row(String.valueOf(i), TITLES[i], TEXTS[i], KINDS[i],
                    i == 0 ? R.drawable.ill_board : R.drawable.ill_medal));
        }
        RowAdapter adapter = new RowAdapter(this);
        adapter.setRows(rows);
        ListView listView = findViewById(R.id.listView);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position == 0) {
                download(exports -> exports.festivalReport());
            } else if (position == 1) {
                download(exports -> exports.medalTable());
            } else if (position == 2) {
                download(exports -> exports.branchList());
            } else if (position == 3) {
                download(exports -> exports.allRegistrations());
            } else {
                download(exports -> exports.fixtures(null));
            }
        });
    }
}
