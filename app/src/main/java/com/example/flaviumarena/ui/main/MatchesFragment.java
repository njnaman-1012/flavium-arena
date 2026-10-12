package com.example.flaviumarena.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.ui.common.BaseFragment;
import com.example.flaviumarena.ui.common.RowAdapter;
import com.example.flaviumarena.ui.common.Ui;
import com.example.flaviumarena.ui.events.RoundDetailActivity;
import com.example.flaviumarena.ui.matches.MatchDetailActivity;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Sport;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Matches tab. "Upcoming" holds every match whose result is not final yet,
 * "Results" holds the finalised ones. Heats and finals of races and throws are listed
 * with the matches. The filter icon opens a bottom sheet with chips.
 */
public class MatchesFragment extends BaseFragment {

    private View root;
    private TabLayout tabs;
    private RowAdapter adapter;
    /** Ids of the rows that are heats or finals, which open a different screen. */
    private final Set<String> roundIds = new HashSet<>();

    // Current filter choices
    private String sportFilter;   // null = all sports
    private boolean onlyMine;     // Asoo Core: only matches assigned to me

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        root = inflater.inflate(R.layout.fragment_matches, container, false);

        tabs = root.findViewById(R.id.tabs);
        tabs.addTab(tabs.newTab().setText("Upcoming"));
        tabs.addTab(tabs.newTab().setText("Results"));

        // Another tab may have asked for a particular view (see HomeFragment.openMatches).
        if (main().openResultsTab) {
            tabs.selectTab(tabs.getTabAt(1));
        }
        onlyMine = main().openOnlyMine && AccessControl.isAsooCore(user());
        main().openResultsTab = false;
        main().openOnlyMine = false;

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showRows();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        adapter = new RowAdapter(requireContext());
        ListView listView = root.findViewById(R.id.listView);
        listView.setEmptyView(root.findViewById(R.id.tvEmpty));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String rowId = adapter.getItem(position).id;
            boolean round = roundIds.contains(rowId);
            Intent intent = new Intent(requireContext(),
                    round ? RoundDetailActivity.class : MatchDetailActivity.class);
            intent.putExtra(round ? BaseActivity.EXTRA_ROUND_ID : BaseActivity.EXTRA_MATCH_ID,
                    rowId);
            startActivity(intent);
        });

        root.findViewById(R.id.btnFilter).setOnClickListener(v -> showFilterSheet());

        Button btnEmpty = root.findViewById(R.id.btnEmptyAction);
        btnEmpty.setVisibility(View.VISIBLE);
        btnEmpty.setOnClickListener(v -> main().selectTab(R.id.nav_discover));
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        showRows();
    }

    private void showRows() {
        boolean results = tabs.getSelectedTabPosition() == 1;

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Match m : repo().getMatches(null)) {
            if (m.isFinal != results) {
                continue;
            }
            if (sportFilter != null && !sportFilter.equals(m.sportName)) {
                continue;
            }
            if (onlyMine && (user() == null || !user().id.equals(m.assignedTo))) {
                continue;
            }
            rows.add(Ui.matchRow(m));
        }
        roundIds.clear();
        for (EventRound r : repo().getRounds(null)) {
            if (r.isFinal != results) {
                continue;
            }
            if (sportFilter != null && !sportFilter.equals(r.sportName)) {
                continue;
            }
            if (onlyMine && (user() == null || !user().id.equals(r.assignedTo))) {
                continue;
            }
            roundIds.add(r.id);
            rows.add(Ui.roundRow(r, true));
        }
        adapter.setRows(rows);

        // Text of the empty state depends on the tab.
        TextView tvEmptyTitle = root.findViewById(R.id.tvEmptyTitle);
        TextView tvEmptyText = root.findViewById(R.id.tvEmptyText);
        tvEmptyTitle.setText(results ? "No results yet" : "No upcoming matches");
        tvEmptyText.setText(results
                ? "Results appear here once a Core has finalised them."
                : "Fixtures appear here as soon as they are scheduled.");

        // Small note under the tabs when a filter is active.
        TextView tvNote = root.findViewById(R.id.tvFilterNote);
        String note = "";
        if (sportFilter != null) {
            note = "Sport: " + sportFilter;
        }
        if (onlyMine) {
            note = (note.isEmpty() ? "" : note + "  |  ") + "Assigned to me";
        }
        tvNote.setText(note);
        tvNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Bottom sheet with one chip per sport and, for an Asoo Core, an "assigned to me" choice. */
    private void showFilterSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(R.layout.sheet_filter, null);
        sheet.setContentView(content);
        // The layout draws its own rounded white background.
        ((View) content.getParent()).setBackgroundResource(android.R.color.transparent);

        ChipGroup chipSports = content.findViewById(R.id.chipSports);
        addChip(chipSports, "All sports", sportFilter == null, () -> sportFilter = null);
        for (Sport sport : repo().getSports()) {
            addChip(chipSports, sport.name, sport.name.equals(sportFilter),
                    () -> sportFilter = sport.name);
        }

        boolean asoo = AccessControl.isAsooCore(user());
        ChipGroup chipScope = content.findViewById(R.id.chipScope);
        content.findViewById(R.id.tvScopeLabel).setVisibility(asoo ? View.VISIBLE : View.GONE);
        chipScope.setVisibility(asoo ? View.VISIBLE : View.GONE);
        addChip(chipScope, "All matches", !onlyMine, () -> onlyMine = false);
        addChip(chipScope, "Assigned to me", onlyMine, () -> onlyMine = true);

        content.findViewById(R.id.btnSheetDone).setOnClickListener(v -> sheet.dismiss());
        content.findViewById(R.id.btnSheetReset).setOnClickListener(v -> {
            sportFilter = null;
            onlyMine = false;
            sheet.dismiss();
        });
        sheet.setOnDismissListener(dialog -> showRows());
        sheet.show();
    }

    private void addChip(ChipGroup group, String text, boolean checked, Runnable onChosen) {
        Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_chip, group, false);
        chip.setId(View.generateViewId());
        chip.setText(text);
        chip.setChecked(checked);
        chip.setOnClickListener(v -> onChosen.run());
        group.addView(chip);
    }
}
