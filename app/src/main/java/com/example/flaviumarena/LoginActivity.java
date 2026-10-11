package com.example.flaviumarena;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;

import com.example.flaviumarena.auth.Session;
import com.example.flaviumarena.data.DevData;
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
        setUpRoleCard(R.id.roleAsoo, R.drawable.ic_live, Role.ASOO_CORE,
                "Match operations and scoring");
        setUpRoleCard(R.id.roleCaptain, R.drawable.ic_users, Role.CAPTAIN,
                "Teams of your own branch");

        findViewById(R.id.btnSetupFaculty).setOnClickListener(v ->
                startActivity(new Intent(this, FacultySetupActivity.class)));
        findViewById(R.id.btnLogin).setOnClickListener(v -> login());
        findViewById(R.id.btnChangeRole).setOnClickListener(v -> showRoles());
        findViewById(R.id.btnFillDev).setOnClickListener(v -> fillDevAccount());

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

    private void fillDevAccount() {
        String[] account = DevData.account(chosenRole);
        if (account != null) {
            etEmail.setText(account[0]);
            etPassword.setText(account[1]);
        }
    }

    private void login() {
        boolean ok = attempt(() -> {
            User user = repo.login(textOf(etEmail), etPassword.getText().toString(), chosenRole);
            Session.login(this, user.id);
            toast("Logged in as " + Role.label(user.role));
        });
        if (ok) {
            openHome();
        }
    }
}
