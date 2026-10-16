package in.flavium.arena.ui.admin;

import android.os.Bundle;
import android.widget.ListView;
import android.widget.TextView;

import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.ui.common.RowAdapter;
import in.flavium.arena.auth.PermissionSelfTest;

import java.util.ArrayList;
import java.util.List;

/** Runs PermissionSelfTest and lists every check with PASS or FAIL. */
public class SelfTestActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_list, "Permission self-test", true);

        TextView tvRunning = findViewById(R.id.tvHeader);
        show(tvRunning, true);
        tvRunning.setText("Running the checks...");

        // The checks hash many passwords, which is deliberately slow, so they run on a
        // background thread and the result is shown when they finish.
        new Thread(() -> {
            List<PermissionSelfTest.Result> results = PermissionSelfTest.run(this);
            runOnUiThread(() -> showResults(results));
        }).start();
    }

    private void showResults(List<PermissionSelfTest.Result> results) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        int passed = 0;
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (PermissionSelfTest.Result r : results) {
            if (r.passed) {
                passed++;
            }
            rows.add(new RowAdapter.Row(null, r.name, r.detail, r.passed ? "PASS" : "FAIL"));
        }

        TextView tvHeader = findViewById(R.id.tvHeader);
        show(tvHeader, true);
        tvHeader.setText(passed + " of " + results.size() + " checks passed.\n"
                + "Each check calls the data layer directly as the public and as each of the four roles, "
                + "without using any screen. It runs on a temporary copy of the sample data, "
                + "so your real data is not changed.");

        RowAdapter adapter = new RowAdapter(this);
        ListView listView = findViewById(R.id.listView);
        listView.setAdapter(adapter);
        adapter.setRows(rows);
    }
}
