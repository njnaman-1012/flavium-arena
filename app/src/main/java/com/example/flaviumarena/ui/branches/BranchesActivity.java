package com.example.flaviumarena.ui.branches;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.ui.common.RowAdapter;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Branch;

import java.util.ArrayList;
import java.util.List;

/**
 * The branches that compete. Everyone can browse them and open a branch's page.
 * Whoever may manage branches (Faculty) also sees an Edit button on every branch, which
 * opens BranchEditActivity (rename, remove, restore), and "Add branch". Removed branches
 * are listed after the active ones, for them only.
 */
public class BranchesActivity extends BaseActivity {

    private RowAdapter adapter;
    private final List<Branch> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_list, "Branches", true);

        adapter = new RowAdapter(this);
        ListView listView = findViewById(R.id.listView);
        listView.setEmptyView(findViewById(R.id.tvEmpty));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(this, BranchPageActivity.class);
            intent.putExtra(EXTRA_BRANCH_ID, shown.get(position).id);
            startActivity(intent);
        });
        Button btnAdd = findViewById(R.id.btnAdd);
        btnAdd.setText("Add branch");
        btnAdd.setOnClickListener(v -> openEditor(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        boolean canManage = AccessControl.canManageBranches(currentUser);
        TextView tvHeader = findViewById(R.id.tvHeader);
        show(tvHeader, true);
        tvHeader.setText(canManage
                ? "Tap a branch to view it, or use Edit to manage it."
                : "Tap a branch to see its teams, fixtures and results.");
        show(findViewById(R.id.btnAdd), canManage);
        load();
    }

    private void load() {
        boolean canManage = AccessControl.canManageBranches(currentUser);
        shown.clear();
        List<RowAdapter.Row> rows = new ArrayList<>();
        // Active branches first; removed ones after them, and only for whoever can restore them.
        for (boolean active : new boolean[]{true, false}) {
            for (Branch b : repo.getBranches()) {
                if (b.active != active || (!b.active && !canManage)) {
                    continue;
                }
                shown.add(b);
                int entries = repo.getRegistrationsOfBranch(currentUser, b.id).size();
                RowAdapter.Row row = new RowAdapter.Row(b.id, b.name,
                        entries + (entries == 1 ? " registration" : " registrations"),
                        b.active ? "" : "Removed");
                row.circleText = b.code.isEmpty() ? null : b.code;
                if (canManage) {
                    row.actionText = "Edit";
                    row.onAction = () -> openEditor(b.id);
                }
                rows.add(row);
            }
        }
        adapter.setRows(rows);
    }

    /** The Edit Branch screen; with no id it adds a branch. */
    private void openEditor(String branchId) {
        Intent intent = new Intent(this, BranchEditActivity.class);
        if (branchId != null) {
            intent.putExtra(EXTRA_BRANCH_ID, branchId);
        }
        startActivity(intent);
    }

    // ----- Downloads -----

    /** The list of branches as a file. */
    @Override
    protected String[] downloadNames() {
        return new String[]{"Branch list with medal counts (CSV)", "Medal table (CSV)"};
    }

    @Override
    protected Download downloadAt(int position) {
        if (position == 0) {
            return exports -> exports.branchList();
        }
        return exports -> exports.medalTable();
    }
}
