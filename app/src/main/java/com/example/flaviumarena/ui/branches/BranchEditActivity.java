package com.example.flaviumarena.ui.branches;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import com.example.flaviumarena.R;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.ui.common.BaseActivity;

/**
 * Add a branch, or edit one: its name and short code, and removing it.
 *
 * Removing a branch deactivates it (repo.setBranchActive): it leaves the list of active
 * branches and the medal table and cannot be chosen for new registrations, but its
 * registrations, fixtures and results are kept, and it can be restored. Nothing is
 * deleted. A branch that still has an active Branch CR cannot be removed; the reason is
 * shown. Only people allowed to manage branches open this screen, and the repository
 * checks again.
 */
public class BranchEditActivity extends BaseActivity {

    private String branchId;
    private EditText etName;
    private EditText etCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        branchId = getIntent().getStringExtra(EXTRA_BRANCH_ID);
        setupScreen(R.layout.activity_branch_edit, branchId == null ? "Add branch" : "Edit branch",
                true);
        etName = findViewById(R.id.etBranchName);
        etCode = findViewById(R.id.etBranchCode);

        if (branchId != null) {
            Branch branch = repo.getBranch(branchId);
            if (branch == null) {
                finish();
                return;
            }
            etName.setText(branch.name);
            etCode.setText(branch.code);
            watchRecord("branches", branchId);
        }

        findViewById(R.id.btnSaveBranch).setOnClickListener(v -> saveCarefully(this::save));
        findViewById(R.id.btnRemoveBranch).setOnClickListener(v -> confirmRemove());
        findViewById(R.id.btnRestoreBranch).setOnClickListener(v -> {
            if (attempt(() -> repo.setBranchActive(currentUser, branchId, true))) {
                toast("Branch restored");
                refreshScreen();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows whether the branch is active. Its name and code are read only once. */
    @Override
    protected void refreshScreen() {
        if (!AccessControl.canManageBranches(currentUser)) {
            toast("Only Faculty can manage branches.");
            finish();
            return;
        }
        Branch branch = branchId == null ? null : repo.getBranch(branchId);
        boolean removed = branch != null && !branch.active;
        show(findViewById(R.id.btnRemoveBranch), branch != null && branch.active);
        show(findViewById(R.id.btnRestoreBranch), removed);
        TextView tvState = findViewById(R.id.tvBranchState);
        show(tvState, removed);
        tvState.setText("This branch has been removed. Its registrations, fixtures and results "
                + "are kept. Restore it to make it active again.");
    }

    private void save() {
        Branch branch = new Branch();
        branch.id = branchId;
        branch.name = textOf(etName);
        branch.code = textOf(etCode);
        if (attempt(() -> repo.saveBranch(currentUser, branch))) {
            toast(branchId == null ? "Branch added" : "Branch saved");
            finish();
        }
    }

    private void confirmRemove() {
        Branch branch = repo.getBranch(branchId);
        if (branch == null) {
            finish();
            return;
        }
        showDialog("Remove " + branch.name + "?\n\nIt will no longer be listed with the active "
                        + "branches or in the medal table, and new teams cannot register for it. "
                        + "Its existing registrations, fixtures and results are kept, not "
                        + "deleted, and you can restore the branch later.",
                R.drawable.ic_close, true, "Remove branch", "Cancel", () -> {
                    if (attempt(() -> repo.setBranchActive(currentUser, branchId, false))) {
                        toast("Branch removed");
                        finish();
                    }
                });
    }
}
