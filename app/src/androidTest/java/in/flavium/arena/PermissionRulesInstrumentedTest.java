package in.flavium.arena;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import in.flavium.arena.auth.PermissionSelfTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Runs the permission self-test against a real SQLite database on a phone or emulator.
 * Every role rule must hold when the repository is called directly.
 */
@RunWith(AndroidJUnit4.class)
public class PermissionRulesInstrumentedTest {

    @Test
    public void everyRoleRuleIsEnforcedByTheRepository() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<PermissionSelfTest.Result> results = PermissionSelfTest.run(context);

        assertFalse("The self-test returned no results", results.isEmpty());
        StringBuilder failures = new StringBuilder();
        for (PermissionSelfTest.Result r : results) {
            if (!r.passed) {
                failures.append("\n").append(r.name).append(": ").append(r.detail);
            }
        }
        assertTrue("Failed checks:" + failures, failures.length() == 0);
    }
}
