package in.flavium.arena.ui.admin;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.auth.AccessControl;
import in.flavium.arena.cloud.Cloud;
import in.flavium.arena.model.AdminAudit;
import in.flavium.arena.model.AppSettings;
import in.flavium.arena.sync.SyncEngine;
import in.flavium.arena.util.DateUtil;

/**
 * Faculty-only screen: the switches that limit Cores, the administrative log, and, only
 * while the festival is not online yet, the one-time step that puts it online.
 */
public class SettingsActivity extends BaseActivity {

    private SwitchCompat swCoreFinalise;
    private SwitchCompat swCoreCaptains;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_settings, "Administrative settings", true);
        swCoreFinalise = findViewById(R.id.swCoreFinalise);
        swCoreCaptains = findViewById(R.id.swCoreCaptains);

        // A click (not every change) saves, so filling in the screen does not write.
        swCoreFinalise.setOnClickListener(v ->
                save(AppSettings.CORE_CAN_FINALISE, swCoreFinalise.isChecked()));
        swCoreCaptains.setOnClickListener(v ->
                save(AppSettings.CORE_CAN_APPOINT_CAPTAINS, swCoreCaptains.isChecked()));
        findViewById(R.id.btnFestivalSetup).setOnClickListener(v -> confirmFestivalSetup());
    }

    @Override
    public void onSyncStatusChanged() {
        if (AccessControl.canChangeSettings(currentUser)) {
            showFestivalSetup();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        if (!AccessControl.canChangeSettings(currentUser)) {
            toast("Only Faculty can open administrative settings.");
            finish();
            return;
        }
        refresh();
    }

    private void save(String key, boolean value) {
        attempt(() -> repo.setFlag(currentUser, key, value));
        refresh();
    }

    private void refresh() {
        swCoreFinalise.setChecked(repo.getFlag(AppSettings.CORE_CAN_FINALISE));
        swCoreCaptains.setChecked(repo.getFlag(AppSettings.CORE_CAN_APPOINT_CAPTAINS));

        StringBuilder text = new StringBuilder();
        for (AdminAudit a : repo.getAdminAudit(currentUser)) {
            text.append(DateUtil.formatTimestamp(a.changedAt)).append("  ")
                    .append(a.actorName).append("\n")
                    .append(a.action).append(": ").append(a.detail).append("\n\n");
        }
        TextView tvLog = findViewById(R.id.tvAdminLog);
        tvLog.setText(text.length() == 0 ? "Nothing recorded yet." : text.toString().trim());
        showFestivalSetup();
    }

    // ----- First-time setup: once for the whole festival -----

    /**
     * Shown only when the server has confirmed that no festival is online yet (or this
     * device's setup was interrupted). Once the festival is online it never appears
     * again, on any device. The security rules let only the device that started it
     * finish it, and nobody can replace a festival that is online.
     */
    private void showFestivalSetup() {
        Cloud cloud = Cloud.get(this);
        SyncEngine.Connection state = cloud.connection();
        boolean empty = state == SyncEngine.Connection.NOT_SET_UP;
        boolean interrupted = state == SyncEngine.Connection.SETTING_UP;
        show(findViewById(R.id.panelFestivalSetup), cloud.isConfigured() && (empty || interrupted));
        TextView tvSetup = findViewById(R.id.tvFestivalSetup);
        Button btnSetup = findViewById(R.id.btnFestivalSetup);
        if (interrupted) {
            tvSetup.setText("Putting the festival online was interrupted. It continues by "
                    + "itself while the app is open; you can also continue it now.");
            btnSetup.setText("Continue");
        } else {
            tvSetup.setText("One-time setup. The festival on this device (sports, categories, "
                    + "registrations, fixtures, results and accounts) becomes the festival "
                    + "every device shares, and other devices then connect by themselves. Do "
                    + "this once, on the device that holds the real festival. A backup copy "
                    + "of this device's data is kept first.");
            btnSetup.setText("Put the festival online");
        }
    }

    private void confirmFestivalSetup() {
        showDialog("Put the festival on THIS device online for everyone? Do this only on the "
                        + "device that holds the real festival data. It is done once.",
                R.drawable.ic_info, true, "Continue", "Cancel", this::askPasswordAndSetUp);
    }

    private void askPasswordAndSetUp() {
        final EditText etPassword = new EditText(this);
        etPassword.setHint("Password (at least 8 characters)");
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Your online password")
                .setMessage("You will log in on every device with " + currentUser.email
                        + " and this password. If you started before, enter the same "
                        + "password again.")
                .setView(etPassword)
                .setPositiveButton("Continue", (dialog, which) -> {
                    final String password = etPassword.getText().toString();
                    runBusy("Putting the festival online. This can take a few minutes...",
                            () -> {
                                Cloud.get(this).uploadFestival(currentUser, password);
                                return true;
                            }, done -> {
                                toast("The festival is online");
                                refresh();
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
