package com.example.flaviumarena.ui.registrations;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.ui.common.RowAdapter;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.util.ImageUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers a team or a participant for a branch, or edits an existing registration.
 *
 *   Faculty and Core: any branch, any time; they also approve, reject and delete.
 *   Branch Captain:   only their own branch (the branch list is locked to it), and only
 *                     until the registration is approved. After approval a captain can
 *                     still change the logo, nothing else.
 */
public class RegistrationEditActivity extends BaseActivity {

    private EditText etName, etTeamLabel, etContactName, etContactPhone, etMembers;
    private Spinner spBranch, spYear;
    private ImageView ivLogo;

    private Category category;
    private Registration registration;
    private final List<Branch> branches = new ArrayList<>();
    /** True when the whole form may be saved; false when only the logo may be changed. */
    private boolean canEditAll;

    // Opens the phone's picture chooser and receives the chosen picture.
    private final ActivityResultLauncher<String> pickLogo = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::onLogoChosen);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String registrationId = getIntent().getStringExtra(EXTRA_REGISTRATION_ID);
        setupScreen(R.layout.activity_registration_edit,
                registrationId == null ? "New registration" : "Registration", true);

        category = repo.getCategory(getIntent().getStringExtra(EXTRA_CATEGORY_ID));
        if (category == null) {
            finish();
            return;
        }

        etName = findViewById(R.id.etRegName);
        etTeamLabel = findViewById(R.id.etTeamLabel);
        etContactName = findViewById(R.id.etContactName);
        etContactPhone = findViewById(R.id.etContactPhone);
        etMembers = findViewById(R.id.etMembers);
        spBranch = findViewById(R.id.spRegBranch);
        spYear = findViewById(R.id.spRegYear);
        ivLogo = findViewById(R.id.ivRegLogo);

        if (registrationId == null) {
            registration = new Registration();
            registration.categoryId = category.id;
            registration.status = Registration.STATUS_PENDING;
            registration.branchId = AccessControl.isCaptain(currentUser)
                    ? currentUser.branchId : getIntent().getStringExtra(EXTRA_BRANCH_ID);
            registration.logoPath = "";
            canEditAll = AccessControl.canRegisterForBranch(userIn(category.sportId),
                    registration.branchId)
                    || AccessControl.canManageRegistrations(userIn(category.sportId));
        } else {
            registration = repo.getRegistration(currentUser, registrationId);
            if (registration == null) {
                finish();
                return;
            }
            canEditAll = AccessControl.canEditRegistration(userIn(category.sportId), registration);
        }
        if (!canEditAll && !AccessControl.canSetLogo(userIn(category.sportId), registration)) {
            toast("You cannot change this registration.");
            finish();
            return;
        }

        TextView tvHint = findViewById(R.id.tvRegHint);
        tvHint.setText(category.name + ": " + category.typeLabel() + ", " + category.sizeText()
                + (category.deadline.isEmpty() ? "" : ". Register by " + category.deadline));

        // A team is named after its branch and year; an individual has their own name.
        show(findViewById(R.id.tvTeamLabelLabel), category.isTeam());
        show(etTeamLabel, category.isTeam());
        show(findViewById(R.id.tvMembersLabel), category.isTeam());
        show(etMembers, category.isTeam());
        show(findViewById(R.id.tvRegNameLabel), !category.isTeam());
        show(etName, !category.isTeam());

        setUpBranchAndYear();
        if (registration.id != null) {
            etName.setText(registration.name);
            etTeamLabel.setText(registration.teamLabel);
            etContactName.setText(registration.contactName);
            etContactPhone.setText(registration.contactPhone);
            etMembers.setText(registration.members);
        }
        showLogo();
        showStatus();

        findViewById(R.id.btnPickLogo).setOnClickListener(v -> pickLogo.launch("image/*"));
        findViewById(R.id.btnSaveReg).setOnClickListener(v -> save());
        findViewById(R.id.btnApprove).setOnClickListener(v ->
                setStatus(Registration.STATUS_APPROVED));
        findViewById(R.id.btnReject).setOnClickListener(v ->
                setStatus(Registration.STATUS_REJECTED));
        findViewById(R.id.btnDeleteReg).setOnClickListener(v ->
                confirm("Delete this registration?", () -> {
                    if (attempt(() -> repo.deleteRegistration(currentUser, registration.id))) {
                        toast("Registration deleted");
                        finish();
                    }
                }));
    }

    /** Branch and year are chosen from lists, so nobody can type an inconsistent name. */
    private void setUpBranchAndYear() {
        boolean captain = AccessControl.isCaptain(currentUser);
        for (Branch b : repo.getBranches()) {
            boolean current = b.id.equals(registration.branchId);
            // A captain gets only their own branch. Others get every active branch,
            // plus the branch already stored even if it has since been deactivated.
            if (captain ? b.id.equals(currentUser.branchId) : (b.active || current)) {
                branches.add(b);
            }
        }
        String[] names = new String[branches.size()];
        for (int i = 0; i < branches.size(); i++) {
            names[i] = branches.get(i).name;
            if (branches.get(i).id.equals(registration.branchId)) {
                spBranch.setSelection(i);
            }
        }
        setSpinnerItems(spBranch, names);
        for (int i = 0; i < branches.size(); i++) {
            if (branches.get(i).id.equals(registration.branchId)) {
                spBranch.setSelection(i);
            }
        }
        spBranch.setEnabled(canEditAll && !captain);

        setSpinnerItems(spYear, Registration.YEAR_LABELS);
        if (registration.year >= Registration.MIN_YEAR
                && registration.year <= Registration.MAX_YEAR) {
            spYear.setSelection(registration.year - 1);
        }
        spYear.setEnabled(canEditAll);
    }

    private void showStatus() {
        TextView tvStatus = findViewById(R.id.tvRegStatus);
        tvStatus.setText("Status: " + registration.status);

        boolean saved = registration.id != null;
        boolean admin = AccessControl.canManageRegistrations(userIn(category.sportId));
        show(findViewById(R.id.btnApprove),
                admin && saved && !Registration.STATUS_APPROVED.equals(registration.status));
        show(findViewById(R.id.btnReject),
                admin && saved && !Registration.STATUS_REJECTED.equals(registration.status));
        show(findViewById(R.id.btnDeleteReg), saved && canEditAll);
        show(findViewById(R.id.btnSaveReg), canEditAll);

        // An approved team is locked for its captain, except for the logo.
        TextView tvNote = findViewById(R.id.tvRegNote);
        show(tvNote, !canEditAll);
        tvNote.setText("This registration is approved, so only its logo can be changed here. "
                + "Ask a Core or Faculty for any other change.");
        etName.setEnabled(canEditAll);
        etTeamLabel.setEnabled(canEditAll);
        etContactName.setEnabled(canEditAll);
        etContactPhone.setEnabled(canEditAll);
        etMembers.setEnabled(canEditAll);
    }

    private void showLogo() {
        Bitmap logo = ImageUtil.load(registration.logoPath);
        if (logo != null) {
            ivLogo.setPadding(0, 0, 0, 0);
            RowAdapter.showLogo(ivLogo, logo);
        }
    }

    private void onLogoChosen(Uri picture) {
        if (picture == null) {
            return; // the chooser was closed without picking anything
        }
        try {
            registration.logoPath = ImageUtil.saveLogo(this, picture);
        } catch (IOException e) {
            showMessage("That picture could not be used: " + e.getMessage());
            return;
        }
        showLogo();
        if (!canEditAll) {
            // Logo-only mode: there is no Save button, so store it straight away.
            if (attempt(() -> repo.setRegistrationLogo(currentUser, registration.id,
                    registration.logoPath))) {
                toast("Logo updated");
            }
        }
    }

    private void save() {
        if (branches.isEmpty()) {
            showMessage("There is no active branch to register for.");
            return;
        }
        registration.branchId = branches.get(spBranch.getSelectedItemPosition()).id;
        registration.year = spYear.getSelectedItemPosition() + 1;
        registration.name = textOf(etName);
        registration.teamLabel = textOf(etTeamLabel);
        registration.contactName = textOf(etContactName);
        registration.contactPhone = textOf(etContactPhone);
        registration.members = etMembers.getText().toString();

        if (attempt(() -> registration.id = repo.saveRegistration(currentUser, registration))) {
            toast("Registration saved");
            finish();
        }
    }

    private void setStatus(String status) {
        if (attempt(() -> repo.setRegistrationStatus(currentUser, registration.id, status))) {
            registration.status = status;
            toast("Registration " + status.toLowerCase());
            showStatus();
        }
    }
}
