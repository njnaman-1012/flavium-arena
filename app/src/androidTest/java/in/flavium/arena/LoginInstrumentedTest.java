package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import in.flavium.arena.data.ArenaException;
import in.flavium.arena.data.ArenaRepository;
import in.flavium.arena.data.DbHelper;
import in.flavium.arena.data.DevData;
import in.flavium.arena.data.PermissionDeniedException;
import in.flavium.arena.data.SqliteArenaRepository;
import in.flavium.arena.data.ValidationException;
import in.flavium.arena.model.Role;
import in.flavium.arena.model.User;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Logging in as a Branch CR on a freshly created database: the right password works,
 * and a wrong password, an unknown email, the wrong role and a deactivated account are
 * each refused. Uses a temporary in-memory database.
 */
@RunWith(AndroidJUnit4.class)
public class LoginInstrumentedTest {

    private DbHelper helper;
    private ArenaRepository repo;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null, true);
        repo = new SqliteArenaRepository(helper);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private interface Action {
        void run();
    }

    private String refusal(Class<? extends ArenaException> kind, Action action) {
        try {
            action.run();
            fail("Expected " + kind.getSimpleName());
            return null;
        } catch (ArenaException e) {
            assertTrue("Expected " + kind.getSimpleName() + " but got " + e, kind.isInstance(e));
            return e.getMessage();
        }
    }

    @Test
    public void branchCrLogsInOnAFreshDatabase() {
        User cr = repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CAPTAIN);
        assertEquals(Role.CAPTAIN, cr.role);
        assertTrue(cr.active);
        assertNotNull("A Branch CR always has a branch", cr.branchId);
        assertEquals(DevData.CAPTAIN_BRANCH, cr.branchName);
    }

    @Test
    public void wrongPasswordAndUnknownEmailGiveTheSameAnswer() {
        String wrong = refusal(ValidationException.class, () ->
                repo.login(DevData.CAPTAIN_EMAIL, "not-the-password", Role.CAPTAIN));
        String unknown = refusal(ValidationException.class, () ->
                repo.login("nobody@flavium.test", DevData.CAPTAIN_PASSWORD, Role.CAPTAIN));
        // The message must not reveal whether the email address has an account.
        assertEquals(wrong, unknown);
    }

    @Test
    public void choosingAnotherRoleIsRefused() {
        refusal(PermissionDeniedException.class, () ->
                repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CORE));
        refusal(PermissionDeniedException.class, () ->
                repo.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CAPTAIN));
    }

    @Test
    public void deactivatedBranchCrCannotLogIn() {
        User faculty = repo.createFirstFaculty("Test Faculty", "faculty@flavium.test",
                "Temp@12345", "faculty@flavium.test");
        User cr = repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CAPTAIN);
        repo.setUserActive(faculty, cr.id, false);
        refusal(PermissionDeniedException.class, () ->
                repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CAPTAIN));
        // Reactivating brings the same account back; nothing was deleted.
        repo.setUserActive(faculty, cr.id, true);
        assertEquals(cr.id,
                repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CAPTAIN).id);
    }
}
