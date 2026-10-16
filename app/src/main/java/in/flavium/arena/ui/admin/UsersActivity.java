package in.flavium.arena.ui.admin;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import in.flavium.arena.cloud.Cloud;
import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.ui.common.RowAdapter;
import in.flavium.arena.auth.AccessControl;
import in.flavium.arena.model.AppSettings;
import in.flavium.arena.model.Branch;
import in.flavium.arena.model.Role;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.User;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Accounts and roles.
 *   Faculty: accounts of every role.
 *   Core:    Asoo Core accounts, and Branch Captains when Faculty allows it.
 * The repository repeats every check, so this screen only decides what to offer.
 */
public class UsersActivity extends BaseActivity {

    private RowAdapter adapter;
    private Spinner spFilter;
    private List<User> users = new ArrayList<>();
    private final List<User> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_list, "Accounts and roles", true);

        TextView tvHeader = findViewById(R.id.tvHeader);
        show(tvHeader, true);
        tvHeader.setText("Tap an account to edit it, reset its password, deactivate it "
                + "or remove it.");

        String[] filters = new String[Role.LABELS.length + 1];
        filters[0] = "All roles";
        System.arraycopy(Role.LABELS, 0, filters, 1, Role.LABELS.length);
        spFilter = findViewById(R.id.spFilter);
        show(spFilter, true);
        setSpinnerItems(spFilter, filters);
        onSpinnerChanged(spFilter, this::showRows);

        adapter = new RowAdapter(this);
        ListView listView = findViewById(R.id.listView);
        listView.setEmptyView(findViewById(R.id.tvEmpty));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                showOptions(shown.get(position)));

        Button btnAdd = findViewById(R.id.btnAdd);
        show(btnAdd, true);
        btnAdd.setText("Add account");
        btnAdd.setOnClickListener(v -> showAccountDialog(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        if (!AccessControl.canViewAccounts(currentUser)) {
            toast("Only Faculty and Cores can manage accounts.");
            finish();
            return;
        }
        load();
    }

    private void load() {
        users = repo.getUsers(currentUser);
        showRows();
    }

    private void showRows() {
        int filter = spFilter.getSelectedItemPosition();
        shown.clear();
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (User u : users) {
            if (filter > 0 && !Role.ALL[filter - 1].equals(u.role)) {
                continue;
            }
            shown.add(u);
            String subtitle = u.email;
            if (Role.CAPTAIN.equals(u.role)) {
                subtitle += "\nBranch: " + u.branchName;
            }
            if (Role.COACH.equals(u.role)) {
                subtitle += "\nSports: " + (u.coachSportNames.isEmpty()
                        ? "none assigned yet" : u.coachSportNames);
            }
            if (!u.active) {
                subtitle += "\nDeactivated";
            }
            rows.add(new RowAdapter.Row(u.id, u.name, subtitle, Role.label(u.role)));
        }
        adapter.setRows(rows);
    }

    /** Roles the logged-in user may give to an account. */
    private List<String> manageableRoles() {
        boolean captains = repo.getFlag(AppSettings.CORE_CAN_APPOINT_CAPTAINS);
        List<String> roles = new ArrayList<>();
        for (String role : Role.ALL) {
            if (AccessControl.canManageAccountsOfRole(currentUser, role, captains)) {
                roles.add(role);
            }
        }
        return roles;
    }

    private void showOptions(User user) {
        if (!manageableRoles().contains(user.role)) {
            showMessage("You can see this account but you are not allowed to change it.");
            return;
        }
        // A Coach has one more choice: the sports they look after.
        boolean coach = Role.COACH.equals(user.role);
        // When the festival is shared, passwords are kept by the server.
        final boolean online = Cloud.get(this).isConnected();
        List<String> labels = new ArrayList<>(Arrays.asList("Edit details and role",
                online ? "Send a password-reset email" : "Reset password",
                user.active ? "Deactivate" : "Reactivate", "Remove account"));
        if (coach) {
            labels.add("Assign sports");
        }
        if (online) {
            labels.add("Create the online login");
        }
        final String[] options = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(user.name)
                .setItems(options, (dialog, which) -> {
                    if (which == options.length - 1 && online) {
                        showOnlineLoginDialog(user);
                    } else if (which == 4 && coach) {
                        showCoachSportsDialog(user.id, user.name, user.coachSportIds);
                    } else if (which == 0) {
                        showAccountDialog(user);
                    } else if (which == 1 && online) {
                        runBusy("Sending the email...", () -> {
                            Cloud.get(this).sendPasswordReset(user.email);
                            return true;
                        }, done -> showMessage("A password-reset email was sent to "
                                + user.email + "."));
                    } else if (which == 1) {
                        showPasswordDialog(user);
                    } else if (which == 2) {
                        confirm((user.active ? "Deactivate " : "Reactivate ") + user.name + "?",
                                () -> {
                                    attempt(() -> repo.setUserActive(currentUser, user.id,
                                            !user.active));
                                    load();
                                });
                    } else {
                        confirm("Remove the account of " + user.name + "?", () -> {
                            attempt(() -> repo.deleteUser(currentUser, user.id));
                            load();
                        });
                    }
                })
                .show();
    }

    /** Add a new account (existing == null) or edit one. */
    private void showAccountDialog(User existing) {
        List<String> roles = manageableRoles();
        List<Branch> branches = new ArrayList<>();
        for (Branch b : repo.getBranches()) {
            if (b.active) {
                branches.add(b);
            }
        }

        View form = getLayoutInflater().inflate(R.layout.dialog_user, null);
        EditText etName = form.findViewById(R.id.etUserName);
        EditText etEmail = form.findViewById(R.id.etUserEmail);
        EditText etPassword = form.findViewById(R.id.etUserPassword);
        Spinner spRole = form.findViewById(R.id.spUserRole);
        Spinner spBranch = form.findViewById(R.id.spUserBranch);
        View branchLabel = form.findViewById(R.id.tvUserBranchLabel);

        String[] roleLabels = new String[roles.size()];
        for (int i = 0; i < roles.size(); i++) {
            roleLabels[i] = Role.label(roles.get(i));
        }
        setSpinnerItems(spRole, roleLabels);

        String[] branchNames = new String[branches.size()];
        for (int i = 0; i < branches.size(); i++) {
            branchNames[i] = branches.get(i).name;
        }
        setSpinnerItems(spBranch, branchNames);

        // The branch is asked for only when the role is Branch Captain.
        onSpinnerChanged(spRole, () -> {
            boolean captain = Role.CAPTAIN.equals(roles.get(spRole.getSelectedItemPosition()));
            show(branchLabel, captain);
            show(spBranch, captain);
        });

        if (existing == null) {
            spRole.setSelection(Math.max(0, roles.indexOf(Role.ASOO_CORE)));
        } else {
            etName.setText(existing.name);
            etEmail.setText(existing.email);
            show(form.findViewById(R.id.tvUserPasswordLabel), false);
            show(etPassword, false);
            spRole.setSelection(Math.max(0, roles.indexOf(existing.role)));
            for (int i = 0; i < branches.size(); i++) {
                if (branches.get(i).id.equals(existing.branchId)) {
                    spBranch.setSelection(i);
                }
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add account" : "Edit account")
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    String role = roles.get(spRole.getSelectedItemPosition());
                    String branchId = null;
                    if (Role.CAPTAIN.equals(role) && !branches.isEmpty()) {
                        branchId = branches.get(spBranch.getSelectedItemPosition()).id;
                    }
                    String chosenBranch = branchId;
                    if (existing == null) {
                        final String[] created = new String[1];
                        String name = textOf(etName);
                        boolean ok = attempt(() -> created[0] = repo.createUser(currentUser,
                                name, textOf(etEmail), etPassword.getText().toString(), role,
                                chosenBranch));
                        // A new Coach has no sports yet: ask for them straight away.
                        if (ok && Role.COACH.equals(role)) {
                            showCoachSportsDialog(created[0], name, new ArrayList<>());
                        }
                        // When the festival is shared, the account also needs a login
                        // on the server, with the same email and password.
                        if (ok && Cloud.get(this).isConnected()) {
                            createOnlineLogin(textOf(etEmail), etPassword.getText().toString(),
                                    created[0]);
                        }
                    } else {
                        attempt(() -> repo.updateUser(currentUser, existing.id, textOf(etName),
                                textOf(etEmail), role, chosenBranch));
                    }
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Tick the sports a Coach looks after. Only Faculty can save this. */
    private void showCoachSportsDialog(String userId, String name, List<String> current) {
        List<Sport> sports = repo.getSports();
        if (sports.isEmpty()) {
            showMessage("Add the sports first. A Coach is then assigned to one or more of "
                    + "them.");
            return;
        }
        String[] names = new String[sports.size()];
        boolean[] ticked = new boolean[sports.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = sports.get(i).name;
            ticked[i] = current.contains(sports.get(i).id);
        }
        new AlertDialog.Builder(this)
                .setTitle("Sports of " + name)
                .setMultiChoiceItems(names, ticked, (dialog, which, isChecked) ->
                        ticked[which] = isChecked)
                .setPositiveButton("Save", (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < ticked.length; i++) {
                        if (ticked[i]) {
                            chosen.add(sports.get(i).id);
                        }
                    }
                    attempt(() -> repo.setCoachSports(currentUser, userId, chosen));
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Asks for a password and gives an existing account its login on the server. */
    private void showOnlineLoginDialog(User user) {
        EditText etPassword = new EditText(this);
        etPassword.setHint("Password for the online login (at least 8 characters)");
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Online login for " + user.name)
                .setMessage("The person signs in with " + user.email + " and this password.")
                .setView(etPassword)
                .setPositiveButton("Create", (dialog, which) ->
                        createOnlineLogin(user.email, etPassword.getText().toString(), user.id))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createOnlineLogin(String email, String password, String userId) {
        runBusy("Creating the online login...", () -> {
            Cloud.get(this).createLogin(email, password, userId);
            return true;
        }, done -> toast("Online login created"));
    }

    private void showPasswordDialog(User user) {
        EditText etPassword = new EditText(this);
        etPassword.setHint("New password (at least 8 characters)");
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        FrameLayout holder = new FrameLayout(this);
        int margin = (int) (24 * getResources().getDisplayMetrics().density);
        holder.setPadding(margin, margin / 2, margin, 0);
        holder.addView(etPassword);

        new AlertDialog.Builder(this)
                .setTitle("Reset password for " + user.name)
                .setView(holder)
                .setPositiveButton("Save", (dialog, which) -> {
                    if (attempt(() -> repo.resetPassword(currentUser, user.id,
                            etPassword.getText().toString()))) {
                        toast("Password changed");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
