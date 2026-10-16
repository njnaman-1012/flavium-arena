package com.example.flaviumarena.ui.admin;

import android.os.Bundle;
import android.widget.TextView;

import com.example.flaviumarena.R;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.cloud.Cloud;
import com.example.flaviumarena.sync.SyncEngine;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.util.DateUtil;

/**
 * Sync status. Read-only: whether this device is online, when it last synced and how
 * many changes are still on their way. Connecting and syncing happen by themselves
 * (see cloud/Cloud), so there is nothing to press here.
 */
public class CloudActivity extends BaseActivity {

    private Cloud cloud;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_cloud, "Sync status", true);
        cloud = Cloud.get(this);
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
        TextView tvNote = findViewById(R.id.tvSyncNote);
        SyncEngine.Status status = cloud.status();
        SyncEngine.Connection state = cloud.connection();

        if (cloud.isConnected()) {
            tvHeadline.setText(cloud.isOnline() ? "Online" : "Offline");
            StringBuilder details = new StringBuilder();
            details.append("Last synced: ").append(status.lastSync == 0 ? "not yet"
                    : DateUtil.formatTimestamp(status.lastSync));
            details.append("\nChanges pending: ").append(status.pending);
            if (cloud.isOnline() && (!status.lastError.isEmpty()
                    || !cloud.listenerProblem().isEmpty())) {
                details.append("\n\nSync temporarily unavailable. We'll retry automatically.");
            }
            tvDetails.setText(details.toString());
            tvNote.setText("Sync happens automatically.");
        } else if (!cloud.isOnline()) {
            tvHeadline.setText("Offline");
            tvDetails.setText("This device connects automatically when it is back online.");
            tvNote.setText("");
        } else if (state == SyncEngine.Connection.LOCAL_DATA_IN_THE_WAY) {
            tvHeadline.setText("Not connected");
            tvDetails.setText("Log in to connect this device to the festival.");
            tvNote.setText("");
        } else if (state == SyncEngine.Connection.NOT_SET_UP
                || state == SyncEngine.Connection.SETTING_UP) {
            tvHeadline.setText("The festival is not online yet");
            tvDetails.setText(AccessControl.isFaculty(currentUser)
                    ? "Faculty puts it online once, from Administrative settings."
                    : "It will appear here as soon as the organisers have put it online.");
            tvNote.setText("");
        } else {
            tvHeadline.setText("Connecting...");
            tvDetails.setText("");
            tvNote.setText("Sync happens automatically.");
        }
    }
}
