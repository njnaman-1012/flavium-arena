package com.example.flaviumarena.ui.branches;

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
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Branch;

import java.util.ArrayList;
import java.util.List;

/**
 * The branches that compete. Everyone can browse them and open a branch's page.
 * Faculty can also add, rename, deactivate and reactivate branches.
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
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!AccessControl.canManageBranches(currentUser)) {
                return false;
            }
            showOptions(shown.get(position));
            return true;
        });

        Button btnAdd = findViewById(R.id.btnAdd);
        btnAdd.setText("Add branch");
        btnAdd.setOnClickListener(v -> showBranchDialog(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean canManage = AccessControl.canManageBranches(currentUser);
        TextView tvHeader = findViewById(R.id.tvHeader);
        show(tvHeader, true);
        tvHeader.setText(canManage
                ? "Tap a branch to open its page. Long-press to rename or deactivate it."
                : "Tap a branch to see its teams, fixtures and results.");
        show(findViewById(R.id.btnAdd), canManage);
        load();
    }

    private void load() {
        boolean canManage = AccessControl.canManageBranches(currentUser);
        shown.clear();
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Branch b : repo.getBranches()) {
            // Deactivated branches are only listed for the people who can reactivate them.
            if (!b.active && !canManage) {
                continue;
            }
            shown.add(b);
            int entries = repo.getRegistrationsOfBranch(currentUser, b.id).size();
            RowAdapter.Row row = new RowAdapter.Row(b.id, b.name,
                    entries + (entries == 1 ? " registration" : " registrations"),
                    b.active ? "" : "Deactivated");
            row.circleText = b.code.isEmpty() ? null : b.code;
            rows.add(row);
        }
        adapter.setRows(rows);
    }

    private void showOptions(Branch branch) {
        String[] options = {"Rename or change code", branch.active ? "Deactivate" : "Reactivate"};
        new AlertDialog.Builder(this)
                .setTitle(branch.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showBranchDialog(branch);
                    } else {
                        confirm((branch.active ? "Deactivate " : "Reactivate ") + branch.name
                                + "?", () -> {
                            attempt(() -> repo.setBranchActive(currentUser, branch.id,
                                    !branch.active));
                            load();
                        });
                    }
                })
                .show();
    }

    /** Add a new branch (existing == null) or edit one. */
    private void showBranchDialog(Branch existing) {
        View form = getLayoutInflater().inflate(R.layout.dialog_branch, null);
        EditText etName = form.findViewById(R.id.etBranchName);
        EditText etCode = form.findViewById(R.id.etBranchCode);
        if (existing != null) {
            etName.setText(existing.name);
            etCode.setText(existing.code);
        }
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add branch" : "Edit branch")
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    Branch branch = new Branch();
                    branch.id = existing == null ? null : existing.id;
                    branch.name = textOf(etName);
                    branch.code = textOf(etCode);
                    attempt(() -> repo.saveBranch(currentUser, branch));
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
