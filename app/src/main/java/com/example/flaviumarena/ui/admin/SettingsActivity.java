package com.example.flaviumarena.ui.admin;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.AdminAudit;
import com.example.flaviumarena.model.AppSettings;
import com.example.flaviumarena.util.DateUtil;

/** Faculty-only screen: the switches that limit Cores, and the administrative log. */
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
    }

    @Override
    protected void onResume() {
        super.onResume();
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
    }
}
