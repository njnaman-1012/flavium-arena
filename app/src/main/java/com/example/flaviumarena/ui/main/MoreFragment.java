package com.example.flaviumarena.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.example.flaviumarena.ui.admin.CloudActivity;
import com.example.flaviumarena.cloud.Cloud;
import com.example.flaviumarena.sync.SyncEngine;
import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.admin.ReportsActivity;
import com.example.flaviumarena.ui.admin.SelfTestActivity;
import com.example.flaviumarena.ui.admin.SettingsActivity;
import com.example.flaviumarena.ui.admin.UsersActivity;
import com.example.flaviumarena.ui.auth.LoginActivity;
import com.example.flaviumarena.ui.branches.BranchPageActivity;
import com.example.flaviumarena.ui.branches.BranchesActivity;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.ui.common.BaseFragment;
import com.example.flaviumarena.ui.common.Ui;
import com.example.flaviumarena.ui.matches.FixturesActivity;
import com.example.flaviumarena.ui.sports.SportsActivity;
import com.example.flaviumarena.ui.standings.MedalsActivity;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.auth.Session;
import com.example.flaviumarena.data.DevData;
import com.example.flaviumarena.model.Role;

/**
 * More tab: profile, organiser tools and settings.
 * The rows are built in code so that each role only gets the rows it may use.
 */
public class MoreFragment extends BaseFragment {

    private View root;
    private LinearLayout container;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent,
                             Bundle savedInstanceState) {
        root = inflater.inflate(R.layout.fragment_more, parent, false);
        container = root.findViewById(R.id.moreContainer);
        ((TextView) root.findViewById(R.id.tvVersion)).setText("Version " + versionName());
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        showProfile();
        buildRows();
    }

    private void showProfile() {
        TextView tvAvatar = root.findViewById(R.id.tvAvatar);
        TextView tvName = root.findViewById(R.id.tvProfileName);
        TextView tvRole = root.findViewById(R.id.tvProfileRole);
        if (user() == null) {
            tvAvatar.setText("G");
            tvName.setText("Guest");
            tvRole.setText("Public visitor (no account needed)");
        } else {
            tvAvatar.setText(Ui.initial(user().name));
            tvName.setText(user().name);
            String role = Role.label(user().role);
            if (AccessControl.isCaptain(user())) {
                role += ", " + user().branchName;
            }
            tvRole.setText(role + "  |  " + user().email);
        }
    }

    private void buildRows() {
        container.removeAllViews();

        // Organiser tools: only the rows the role is allowed to use.
        if (AccessControl.isStaff(user())) {
            addSection("Organiser tools");
            if (AccessControl.canManageSports(user())) {
                addRow(R.drawable.ic_trophy, "Manage sports", "", false,
                        () -> open(SportsActivity.class));
            }
            if (AccessControl.canViewAccounts(user())) {
                addRow(R.drawable.ic_users, "Accounts and roles", "", false,
                        () -> open(UsersActivity.class));
            }
            if (AccessControl.canChangeSettings(user())) {
                addRow(R.drawable.ic_shield, "Administrative settings", "", false,
                        () -> open(SettingsActivity.class));
            }
            if (AccessControl.isAsooCore(user())) {
                addRow(R.drawable.ic_matches, "My assigned matches", "", false, () -> {
                    main().openOnlyMine = true;
                    main().selectTab(R.id.nav_matches);
                });
            }
        }

        // A Coach's own tools: the sports Faculty assigned to them.
        if (AccessControl.isCoach(user())) {
            addSection("My sports");
            addRow(R.drawable.ic_trophy, user().coachSportNames.isEmpty()
                            ? "No sports assigned yet" : user().coachSportNames, "", false,
                    () -> main().selectTab(R.id.nav_discover));
        }

        // A captain's own tools.
        if (AccessControl.isCaptain(user())) {
            addSection("My branch");
            addRow(R.drawable.ic_users, "Manage " + user().branchName, "", false,
                    this::openMyBranch);
        }

        addSection("Festival");
        addRow(R.drawable.ic_trophy, "Medal table", "", false, () -> open(MedalsActivity.class));
        addRow(R.drawable.ic_users, "Branches", "", false, () -> open(BranchesActivity.class));
        addRow(R.drawable.ic_live, "Sync status", syncLabel(), false,
                () -> open(CloudActivity.class));
        addRow(R.drawable.ic_download, "Reports and downloads", "", false,
                () -> open(ReportsActivity.class));
        addRow(R.drawable.ic_search, "Search all fixtures", "", false,
                () -> open(FixturesActivity.class));
        addRow(R.drawable.ic_trophy, "Browse sports", "", false,
                () -> main().selectTab(R.id.nav_discover));

        addSection("Settings");
        if (DevData.AVAILABLE) {
            addRow(R.drawable.ic_shield, "Permission self-test", "", false,
                    () -> open(SelfTestActivity.class));
        }
        addRow(R.drawable.ic_slides, "Onboarding", "", false,
                () -> open(OnboardingActivity.class));
        addRow(R.drawable.ic_info, "About", "", false, () -> main().showMessage(
                "FLAVIUM Arena " + versionName() + "\nSports, fixtures, live scores, points "
                        + "tables and medals for the FLAVIUM festival, kept up to date on "
                        + "every device."));
        if (user() != null) {
            addRow(R.drawable.ic_edit, "Change my password", "", false,
                    () -> main().showChangePasswordDialog());
        }
        if (user() == null) {
            addRow(R.drawable.ic_login, "Organiser login", "", false,
                    () -> open(LoginActivity.class));
        } else {
            addRow(R.drawable.ic_logout, "Log out", "", true, () ->
                    main().showDialog("Are you sure you want to log out?", R.drawable.ic_logout,
                            false, "Log out", "Cancel", () -> {
                                Session.logout(requireContext(), repo(), user());
                                Cloud.get(requireContext()).signOut();
                                main().openHome();
                            }));
        }
    }

    /** The installed version, as Google Play shows it. */
    private String versionName() {
        try {
            return requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Synced, syncing, offline, or how many changes are still waiting to be sent. */
    private String syncLabel() {
        Cloud cloud = Cloud.get(requireContext());
        if (!cloud.isConfigured()) {
            return "";
        }
        if (!cloud.isConnected()) {
            return cloud.isOnline() ? "" : "Offline";
        }
        if (!cloud.isOnline()) {
            return "Offline";
        }
        if (cloud.isSyncing()) {
            return "Syncing";
        }
        int pending = cloud.status().pending;
        return pending == 0 ? "Synced" : pending + " waiting";
    }

    private void openMyBranch() {
        Intent intent = new Intent(requireContext(), BranchPageActivity.class);
        intent.putExtra(BaseActivity.EXTRA_BRANCH_ID, user().branchId);
        startActivity(intent);
    }

    private void open(Class<?> screen) {
        startActivity(new Intent(requireContext(), screen));
    }

    private void addSection(String title) {
        TextView tv = new TextView(requireContext());
        tv.setText(title);
        tv.setTextSize(18);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        float density = getResources().getDisplayMetrics().density;
        params.topMargin = (int) (26 * density);
        params.bottomMargin = (int) (4 * density);
        container.addView(tv, params);
    }

    private void addRow(int icon, String label, String value, boolean danger, Runnable onClick) {
        View row = getLayoutInflater().inflate(R.layout.item_setting_row, container, false);
        ImageView ivIcon = row.findViewById(R.id.ivSettingIcon);
        TextView tvLabel = row.findViewById(R.id.tvSettingLabel);
        TextView tvValue = row.findViewById(R.id.tvSettingValue);

        ivIcon.setImageResource(icon);
        tvLabel.setText(label);
        tvValue.setText(value);
        if (danger) {
            int red = ContextCompat.getColor(requireContext(), R.color.danger);
            tvLabel.setTextColor(red);
            ivIcon.setColorFilter(red);
        }
        row.setOnClickListener(v -> onClick.run());
        container.addView(row);
    }
}
