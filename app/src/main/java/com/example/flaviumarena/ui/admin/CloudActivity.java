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
 * Online sharing. Shows whether this device is connected to the shared festival and
 * offers the two ways to connect it:
 *
 *   Upload   for the one device that holds the real data. Faculty only, once.
 *   Join     for every other device. Its own festival data is replaced by the shared
 *            one, after a copy of the local database has been kept.
 *
 * Both ask for confirmation first, because neither can be undone from the app.
 */
public class CloudActivity extends BaseActivity {

    private Cloud cloud;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_cloud, "Online sharing", true);
        cloud = Cloud.get(this);

        findViewById(R.id.btnCloudSync).setOnClickListener(v ->
                runBusy("Synchronising...", () -> cloud.syncNow(), ok -> {
                    toast(ok ? "Up to date" : "The server could not be reached");
                    refreshScreen();
                }));
        findViewById(R.id.btnCloudJoin).setOnClickListener(v -> confirmJoin());
        findViewById(R.id.btnCloudUpload).setOnClickListener(v -> confirmUpload());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current state. Runs when the screen opens and when the state changes. */
    @Override
    protected void refreshScreen() {
        TextView tvStatus = findViewById(R.id.tvCloudStatus);
        TextView tvHelp = findViewById(R.id.tvCloudHelp);
        Button btnSync = findViewById(R.id.btnCloudSync);
        Button btnJoin = findViewById(R.id.btnCloudJoin);
        Button btnUpload = findViewById(R.id.btnCloudUpload);
        show(btnSync, false);
        show(btnJoin, false);
        show(btnUpload, false);

        if (!cloud.isConfigured()) {
            tvStatus.setText("Not available in this build.");
            tvHelp.setText("This app was built without a Firebase configuration, so it keeps "
                    + "its data on this device only. See the README for how to set it up.");
            return;
        }
        SyncEngine.Status status = cloud.status();
        boolean faculty = AccessControl.isFaculty(currentUser);
        if (status.link == SyncEngine.LINK_CONNECTED) {
            StringBuilder text = new StringBuilder("Connected to the shared festival.");
            text.append("\nChanges waiting to be sent: ").append(status.pending);
            text.append("\nLast complete exchange: ").append(status.lastSync == 0 ? "not yet"
                    : DateUtil.formatTimestamp(status.lastSync));
            if (!status.lastError.isEmpty()) {
                text.append("\nLast problem: ").append(status.lastError);
            }
            tvStatus.setText(text.toString());
            tvHelp.setText("Changes made here are sent automatically, and changes made on "
                    + "other devices appear here while the app is open. Without a connection "
                    + "the app keeps working and sends the changes later.");
            show(btnSync, true);
        } else if (status.link == SyncEngine.LINK_UPLOADING) {
            tvStatus.setText("The upload of this device's festival was interrupted.\n"
                    + "Records still to be sent: " + status.pending);
            tvHelp.setText("Continue the upload. Nothing is sent twice. Other devices can "
                    + "join only when it has finished.");
            btnUpload.setText("Continue the upload");
            show(btnUpload, faculty);
        } else {
            tvStatus.setText("This device is not connected. Its data is only on this device.");
            tvHelp.setText("ONE device uploads its festival: the one that holds the real "
                    + "data. Only Faculty can do that, and only while the server is empty.\n\n"
                    + "Every other device joins. Joining replaces the festival data on this "
                    + "device with the shared one; a copy of the local database is kept "
                    + "first.");
            btnUpload.setText("Upload this device's festival");
            show(btnJoin, true);
            show(btnUpload, faculty);
        }
    }

    // ----- Upload: the first device -----

    private void confirmUpload() {
        showDialog("Upload the festival on THIS device as the shared festival? Every other "
                        + "device will receive this data. Do this on one device only.",
                R.drawable.ic_info, false, "Continue", "Cancel", this::askPasswordAndUpload);
    }

    private void askPasswordAndUpload() {
        final EditText etPassword = new EditText(this);
        etPassword.setHint("Password (at least 8 characters)");
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Your online account")
                .setMessage("You will sign in on every device with " + currentUser.email
                        + " and this password. If you uploaded before, enter the same "
                        + "password again.")
                .setView(etPassword)
                .setPositiveButton("Upload", (dialog, which) -> {
                    final String password = etPassword.getText().toString();
                    runBusy("Uploading the festival. This can take a few minutes...", () -> {
                        cloud.uploadFestival(currentUser, password);
                        return true;
                    }, done -> {
                        toast("The festival is shared");
                        refreshScreen();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ----- Join: every other device -----

    private void confirmJoin() {
        String question = cloud.hasLocalFestival()
                ? "Join the shared festival? The sports, registrations, fixtures and results "
                + "on THIS device will be replaced by the shared ones. A copy of the local "
                + "database is kept on this device first. You will have to log in again."
                : "Join the shared festival? This device will receive its data.";
        showDialog(question, R.drawable.ic_info, cloud.hasLocalFestival(), "Join", "Cancel",
                () -> runBusy("Joining the shared festival...", () -> cloud.joinFestival(),
                        backup -> {
                            // Local accounts were replaced, so the old login is gone.
                            Session.logout(this, repo, currentUser);
                            showDialog("This device now shares the festival.\n\nA copy of "
                                            + "the earlier data is in:\n" + backup.getPath(),
                                    R.drawable.ic_check, false, "OK", null, this::openHome);
                        }));
    }
}
