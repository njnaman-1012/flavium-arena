package in.flavium.arena.ui.auth;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;

import in.flavium.arena.cloud.Cloud;
import in.flavium.arena.sync.SyncEngine;
import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.ui.common.DevTools;
import in.flavium.arena.auth.Session;
import in.flavium.arena.data.ArenaException;
import in.flavium.arena.data.SessionActiveException;
import in.flavium.arena.model.Role;
import in.flavium.arena.model.User;

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
        // Debug builds only: a helper for the development account of the role.
        DevTools.showLoginHelper(this, findViewById(R.id.panelDev), role, etEmail, etPassword);
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
                } else if (connection == SyncEngine.Connection.LOCAL_DATA_IN_THE_WAY) {
                    switchToOnlineFestival(cloud, takeOver);
                } else {
                    loginLocally(takeOver);
                }
            });
            return;
        }
        loginLocally(takeOver);
    }

    /**
     * The festival is online, but this device holds festival data of its own that is not
     * part of it (it was used before it could connect). Logging in means using the
     * online festival, so the person is asked first and a copy of this device's data is
     * kept; nothing is replaced without that.
     */
    private void switchToOnlineFestival(Cloud cloud, boolean takeOver) {
        showDialog("This device has festival data that is not part of the festival online. "
                        + "To log in, it switches to the online festival. A backup copy of "
                        + "this device's data is kept on the device.", R.drawable.ic_info, true,
                "Switch and log in", "Cancel",
                () -> runBusy("Connecting...", () -> {
                    cloud.useOnlineFestival();
                    return true;
                }, done -> loginOnline(cloud, takeOver)));
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
