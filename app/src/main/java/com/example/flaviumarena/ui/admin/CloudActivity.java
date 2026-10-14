package com.example.flaviumarena.ui.admin;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.flaviumarena.R;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.auth.Session;
import com.example.flaviumarena.cloud.Cloud;
import com.example.flaviumarena.sync.SyncEngine;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.util.DateUtil;

/**
 * Online sync. Shows whether this device is online, whether its changes have all been
 * sent, and when it last synced. Devices connect to the online festival by themselves
 * and keep in step without anyone pressing anything; this screen only reports it.
 *
 * Two things can still be done here, each only in its own situation:
 *   Set up online sync      once for the whole festival, by Faculty, while nothing is
 *                           online yet: this device's data becomes the online festival
 *   Use the online festival a device that holds festival data of its own, made before
 *                           it was connected, switches to the online festival; its own
 *                           data is never replaced without this decision
 */
public class CloudActivity extends BaseActivity {

    private Cloud cloud;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_cloud, "Online sync", true);
        cloud = Cloud.get(this);

        findViewById(R.id.btnSyncNow).setOnClickListener(v ->
                runBusy("Syncing...", () -> cloud.syncNow(), ok -> {
                    toast(ok ? "All changes synced" : "The server could not be reached");
                    refreshScreen();
                }));
        findViewById(R.id.btnSetUpSync).setOnClickListener(v -> confirmSetUp());
        findViewById(R.id.btnUseOnline).setOnClickListener(v -> confirmUseOnline());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    @Override
    public void onSyncStatusChanged() {
        refreshScreen();
    }

    /** Shows the current state. Runs when the screen opens and whenever it changes. */
    @Override
    protected void refreshScreen() {
        TextView tvHeadline = findViewById(R.id.tvSyncHeadline);
        TextView tvDetails = findViewById(R.id.tvSyncDetails);
        TextView tvHelp = findViewById(R.id.tvSyncHelp);
        Button btnSetUp = findViewById(R.id.btnSetUpSync);
        Button btnUseOnline = findViewById(R.id.btnUseOnline);
        Button btnSyncNow = findViewById(R.id.btnSyncNow);
        show(btnSetUp, false);
        show(btnUseOnline, false);
        show(btnSyncNow, false);

        if (!cloud.isConfigured()) {
            tvHeadline.setText("Online sync is not available");
            tvDetails.setText("This app was built without a Firebase configuration.");
            tvHelp.setText("It keeps its data on this device only. See the README for how "
                    + "to set it up.");
            return;
        }
        SyncEngine.Status status = cloud.status();
        boolean faculty = AccessControl.isFaculty(currentUser);
        String connection = cloud.isOnline() ? "Online" : "Offline";
        SyncEngine.Connection state = cloud.connection();

        if (cloud.isConnected()) {
            String headline;
            if (!cloud.isOnline()) {
                headline = "Offline";
            } else if (cloud.isSyncing()) {
                headline = "Syncing...";
            } else if (status.pending > 0) {
                headline = status.pending == 1 ? "1 change waiting"
                        : status.pending + " changes waiting";
            } else {
                headline = "All changes synced";
            }
            tvHeadline.setText(headline);
            StringBuilder details = new StringBuilder(connection);
            details.append("\nWaiting to be sent: ").append(status.pending);
            details.append("\nLast synced: ").append(status.lastSync == 0 ? "not yet"
                    : DateUtil.formatTimestamp(status.lastSync));
            if (!status.lastError.isEmpty()) {
                details.append("\nLast problem: ").append(status.lastError)
                        .append(" Trying again by itself.");
            }
            if (!cloud.listenerProblem().isEmpty()) {
                details.append("\nLive updates paused: ").append(cloud.listenerProblem())
                        .append(" Starting again shortly.");
            }
            tvDetails.setText(details.toString());
            tvHelp.setText("Changes made on this device are sent by themselves, and changes "
                    + "made on other devices appear here while the app is open. Without a "
                    + "connection the app keeps working and sends the changes when the "
                    + "connection returns.");
            show(btnSyncNow, true);
        } else if (state == SyncEngine.Connection.SETTING_UP) {
            tvHeadline.setText("Setting up online sync");
            tvDetails.setText(connection + "\nRecords still to be sent: " + status.pending);
            tvHelp.setText("This device is putting its festival online. It continues by "
                    + "itself while the app is open; nothing is sent twice. Other devices "
                    + "connect as soon as it has finished.");
            btnSetUp.setText("Continue now");
            show(btnSetUp, faculty);
        } else if (state == SyncEngine.Connection.NOT_SET_UP) {
            tvHeadline.setText("Online sync is not set up yet");
            tvDetails.setText(connection);
            tvHelp.setText("Until it is, this device keeps its data to itself.\n\nOnline sync "
                    + "is set up once for the whole festival, by Faculty, on the device that "
                    + "holds the festival's data. After that every device, phones and "
                    + "emulators alike, connects by itself and stays in step.");
            btnSetUp.setText("Set up online sync with this device's data");
            show(btnSetUp, faculty);
        } else if (state == SyncEngine.Connection.LOCAL_DATA_IN_THE_WAY) {
            tvHeadline.setText("This device has data of its own");
            tvDetails.setText(connection);
            tvHelp.setText("The festival is online, but this device holds festival data "
                    + "that is not part of it, so it has not connected.\n\nUse the online "
                    + "festival to connect it. A copy of this device's own data is kept on "
                    + "the device first.");
            show(btnUseOnline, true);
        } else {
            tvHeadline.setText(cloud.isOnline() ? "Connecting..." : "Offline");
            tvDetails.setText(connection);
            tvHelp.setText("This device connects to the online festival by itself as soon "
                    + "as the server can be reached.");
            show(btnSyncNow, cloud.isOnline());
        }
    }

    // ----- Setting up online sync: once for the whole festival -----

    private void confirmSetUp() {
        showDialog("Set up online sync with the festival on THIS device? Its data becomes "
                        + "the online festival that every device shares. This is done once, "
                        + "on the device that holds the real data.",
                R.drawable.ic_info, false, "Continue", "Cancel", this::askPasswordAndSetUp);
    }

    private void askPasswordAndSetUp() {
        final EditText etPassword = new EditText(this);
        etPassword.setHint("Password (at least 8 characters)");
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Your online account")
                .setMessage("You will log in on every device with " + currentUser.email
                        + " and this password. If you started the set-up before, enter the "
                        + "same password again.")
                .setView(etPassword)
                .setPositiveButton("Set up", (dialog, which) -> {
                    final String password = etPassword.getText().toString();
                    runBusy("Putting the festival online. This can take a few minutes...",
                            () -> {
                                cloud.uploadFestival(currentUser, password);
                                return true;
                            }, done -> {
                                toast("Online sync is set up");
                                refreshScreen();
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ----- A device with data of its own -----

    private void confirmUseOnline() {
        showDialog("Use the online festival on this device? Its sports, registrations, "
                        + "fixtures, results and accounts are replaced by the online ones. A "
                        + "copy of this device's data is kept first. You will have to log in "
                        + "again.", R.drawable.ic_info, true, "Use the online festival",
                "Cancel",
                () -> runBusy("Connecting...", () -> {
                    cloud.useOnlineFestival();
                    return true;
                }, done -> {
                    // The accounts on this device were replaced: the old login is gone.
                    if (currentUser != null) {
                        Session.logout(this, repo, currentUser);
                    }
                    refreshScreen();
                }));
    }
}
