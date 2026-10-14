package com.example.flaviumarena.ui.auth;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;

import com.example.flaviumarena.cloud.Cloud;
import com.example.flaviumarena.sync.SyncEngine;
import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.auth.Session;
import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.data.DevData;
import com.example.flaviumarena.data.SessionActiveException;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.User;

/**
 * Organiser login in two steps: choose a role, then enter email and password.
 *
 * The chosen role is only what the person expects to be. The repository checks the
 * password and then compares the chosen role with the role stored for the account;
 * if they differ the login is refused.
 */
public class LoginActivity extends BaseActivity {

    private View panelRoles;
    private View panelForm;
    private EditText etEmail;
    private EditText etPassword;
    /** The role card that was tapped, or null while the cards are showing. */
    private String chosenRole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_login, "Organiser login", true);

        panelRoles = findViewById(R.id.panelRoles);
        panelForm = findViewById(R.id.panelForm);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);

        setUpRoleCard(R.id.roleFaculty, R.drawable.ic_shield, Role.FACULTY,
                "Super admin of the festival");
        setUpRoleCard(R.id.roleCore, R.drawable.ic_trophy, Role.CORE,
                "Festival administration");
        setUpRoleCard(R.id.roleCoach, R.drawable.ic_result, Role.COACH,
                "The sports assigned to you");
        setUpRoleCard(R.id.roleAsoo, R.drawable.ic_live, Role.ASOO_CORE,
                "Match operations and scoring");
        setUpRoleCard(R.id.roleCaptain, R.drawable.ic_users, Role.CAPTAIN,
                "Teams of your own branch");

        findViewById(R.id.btnSetupFaculty).setOnClickListener(v ->
                startActivity(new Intent(this, FacultySetupActivity.class)));
        findViewById(R.id.btnLogin).setOnClickListener(v -> login(false));
        findViewById(R.id.btnChangeRole).setOnClickListener(v -> showRoles());
        findViewById(R.id.btnFillDev).setOnClickListener(v -> fillDevAccount());
        findViewById(R.id.btnRepairDev).setOnClickListener(v -> repairDevAccount());

        // Back from the form returns to the role cards instead of closing the screen.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (chosenRole != null) {
                    showRoles();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        // The set-up link is offered only while there is no Faculty account at all.
        show(findViewById(R.id.btnSetupFaculty), !repo.hasFaculty());
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    private void setUpRoleCard(int cardId, int icon, String role, String text) {
        View card = findViewById(cardId);
        ImageView picture = card.findViewById(R.id.ivLink);
        int padding = (int) (13 * getResources().getDisplayMetrics().density);
        picture.setImageResource(icon);
        picture.setBackgroundResource(R.drawable.bg_circle_soft);
        picture.setPadding(padding, padding, padding, padding);
        picture.setColorFilter(ContextCompat.getColor(this, R.color.primary));
        ((TextView) card.findViewById(R.id.tvLinkTitle)).setText(Role.label(role) + " Login");
        ((TextView) card.findViewById(R.id.tvLinkText)).setText(text);
        card.setOnClickListener(v -> showForm(role));
    }

    private void showRoles() {
        chosenRole = null;
        setScreenTitle("Organiser login");
        show(panelRoles, true);
        show(panelForm, false);
    }

    private void showForm(String role) {
        chosenRole = role;
        setScreenTitle(Role.label(role) + " Login");
        etEmail.setText("");
        etPassword.setText("");
        show(panelRoles, false);
        show(panelForm, true);
        // Development accounts exist only in debug builds, and not for Faculty.
        boolean devBuild = getResources().getBoolean(R.bool.seed_dev_data);
        show(findViewById(R.id.panelDev), devBuild && DevData.account(role) != null);
    }

    /**
     * This device shares the festival, so the server checks the password and decides
     * which account it is. The local password is not used at all.
     */
    private void loginOnline(final Cloud cloud, final boolean takeOver) {
        final String email = textOf(etEmail);
        final String password = etPassword.getText().toString();
        final String role = chosenRole;
        final String[] signedInElsewhere = new String[1];
        runBusy("Signing in...", () -> {
            try {
                return cloud.signIn(email, password, role, takeOver);
            } catch (SessionActiveException e) {
                signedInElsewhere[0] = e.getMessage();
                return null;
            }
        }, user -> {
            if (user == null) {
                showDialog(signedInElsewhere[0], R.drawable.ic_info, false,
                        "Sign out the other device", "Cancel", () -> login(true));
                return;
            }
            Session.login(this, user);
            toast("Logged in as " + Role.label(user.role));
            openHome();
        });
    }

    private void fillDevAccount() {
        String[] account = DevData.account(chosenRole);
        if (account == null) {
            return;
        }
        etEmail.setText(account[0]);
        etPassword.setText(account[1]);
        // A database that was upgraded from an older version of the app may not have
        // this account: say so now instead of letting the login fail without a reason.
        if (!DevData.exists(this, chosenRole)) {
            showMessage("This development account is not in the database of this device. "
                    + "Tap \"Create or repair this development account\" first.");
        }
    }

    /** Debug builds only: adds the development account, or makes it usable again. */
    private void repairDevAccount() {
        String role = chosenRole;
        showDialog("Create the development " + Role.label(role) + " account, or reset its "
                        + "password and reactivate it if it already exists? No other data "
                        + "is changed.", R.drawable.ic_info, false, "Continue", "Cancel", () -> {
                    final String[] done = new String[1];
                    if (attempt(() -> done[0] = DevData.createOrRepair(this, role))) {
                        fillDevAccount();
                        showMessage(done[0]);
                    }
                });
    }

    /**
     * An account has one live session at a time. When the account is already logged in
     * somewhere else the login is refused, and the person can choose to sign that other
     * session out, which runs this again with takeOver = true.
     *
     * When the festival is online, the device first connects to it (a new installation
     * does so by itself) and the server checks the password: the same account then works
     * on every device. Before online sync is set up, or without any connection on a
     * device that never connected, the login uses this device's own accounts.
     */
    private void login(boolean takeOver) {
        final Cloud cloud = Cloud.get(this);
        if (cloud.isConnected()) {
            loginOnline(cloud, takeOver);
            return;
        }
        if (cloud.isConfigured()) {
            runBusy("Connecting...", cloud::connectForLogin, connection -> {
                if (connection == SyncEngine.Connection.CONNECTED) {
                    loginOnline(cloud, takeOver);
                } else {
                    loginLocally(takeOver);
                }
            });
            return;
        }
        loginLocally(takeOver);
    }

    /** The login checked against this device's own accounts. */
    private void loginLocally(boolean takeOver) {
        try {
            User user = repo.login(textOf(etEmail), etPassword.getText().toString(),
                    chosenRole, Build.MODEL, takeOver);
            Session.login(this, user);
            toast("Logged in as " + Role.label(user.role));
            openHome();
        } catch (SessionActiveException e) {
            showDialog(e.getMessage(), R.drawable.ic_info, false, "Sign out the other session",
                    "Cancel", () -> login(true));
        } catch (ArenaException e) {
            showMessage(e.getMessage());
        }
    }
}
