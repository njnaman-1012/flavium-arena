package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import in.flavium.arena.data.ArenaException;
import in.flavium.arena.data.ArenaRepository;
import in.flavium.arena.data.DbHelper;
import in.flavium.arena.data.DevData;
import in.flavium.arena.data.PermissionDeniedException;
import in.flavium.arena.data.SessionActiveException;
import in.flavium.arena.data.SqliteArenaRepository;
import in.flavium.arena.data.ValidationException;
import in.flavium.arena.model.Role;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.User;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One live session per account, inside one database. Two "devices" are modelled as two
 * logins that keep their own session. Uses a temporary in-memory database.
 */
@RunWith(AndroidJUnit4.class)
public class SessionInstrumentedTest {

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

    private void refused(Class<? extends ArenaException> kind, Action action) {
        try {
            action.run();
            fail("Expected " + kind.getSimpleName());
        } catch (ArenaException e) {
            assertTrue("Expected " + kind.getSimpleName() + " but got " + e, kind.isInstance(e));
        }
    }

    private User core(String device, boolean takeOver) {
        return repo.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE, device,
                takeOver);
    }

    /** A protected action: only a Core with a live session may add a sport. */
    private void addSport(User actor, String name) {
        Sport sport = new Sport();
        sport.name = name;
        sport.description = "";
        repo.saveSport(actor, sport);
    }

    @Test
    public void secondLoginIsRefusedWhileTheFirstSessionIsLive() {
        User first = core("Phone A", false);
        assertNotNull(first.sessionId);
        refused(SessionActiveException.class, () -> core("Phone B", false));
        // The first session is untouched by the refused attempt.
        addSport(first, "Kabaddi");
        assertEquals(first.id, repo.resumeSession(first.sessionId).id);
    }

    @Test
    public void aWrongPasswordLearnsNothingAboutTheSession() {
        core("Phone A", false);
        // Not SessionActiveException: the password is checked before anything else.
        refused(ValidationException.class, () -> repo.login(DevData.CORE_EMAIL, "wrong-one",
                Role.CORE, "Phone B", true));
    }

    @Test
    public void takeOverSignsTheOldSessionOutAtOnce() {
        User first = core("Phone A", false);
        User second = core("Phone B", true);
        assertNotEquals(first.sessionId, second.sessionId);

        // The old session has no identity any more: protected actions are refused.
        assertNull(repo.resumeSession(first.sessionId));
        assertEquals(SqliteArenaRepository.END_TAKEN_OVER,
                repo.getSessionEndReason(first.sessionId));
        refused(PermissionDeniedException.class, () -> addSport(first, "Kabaddi"));
        refused(PermissionDeniedException.class, () -> repo.getUsers(first));

        // The new session works.
        addSport(second, "Kho Kho");
        assertEquals("", repo.getSessionEndReason(second.sessionId));
    }

    @Test
    public void logoutAllowsTheNextLogin() {
        User first = core("Phone A", false);
        repo.logout(first);
        assertNull(repo.resumeSession(first.sessionId));
        refused(PermissionDeniedException.class, () -> addSport(first, "Kabaddi"));
        // No take-over needed now.
        User second = core("Phone B", false);
        addSport(second, "Kabaddi");
    }

    @Test
    public void anIdentityWithoutASessionIsThePublic() {
        User real = core("Phone A", false);
        // Someone who copies the id and role of an account but has no session.
        User forged = new User();
        forged.id = real.id;
        forged.role = Role.FACULTY;
        forged.name = real.name;
        refused(PermissionDeniedException.class, () -> addSport(forged, "Kabaddi"));
        forged.sessionId = "0000";
        refused(PermissionDeniedException.class, () -> addSport(forged, "Kabaddi"));
        // A real session of one account cannot be used with the id of another.
        User asoo = repo.login(DevData.ASOO_EMAIL, DevData.ASOO_PASSWORD, Role.ASOO_CORE);
        forged.sessionId = asoo.sessionId;
        refused(PermissionDeniedException.class, () -> addSport(forged, "Kabaddi"));
    }

    @Test
    public void anAbandonedSessionExpiresAndStopsBlocking() {
        User first = core("Lost phone", false);
        // Pretend the session was last used eight days ago.
        ContentValues old = new ContentValues();
        old.put("last_seen_at", System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000);
        helper.getWritableDatabase().update("sessions", old, "id = ?",
                new String[]{first.sessionId});

        User second = core("Phone B", false);
        assertNotNull(second.sessionId);
        assertEquals(SqliteArenaRepository.END_EXPIRED,
                repo.getSessionEndReason(first.sessionId));
        refused(PermissionDeniedException.class, () -> addSport(first, "Kabaddi"));
    }

    @Test
    public void deactivationAndPasswordResetSignTheAccountOut() {
        User faculty = repo.createFirstFaculty("Test Faculty", "faculty@flavium.test",
                DevData.newPassword(), "faculty@flavium.test");
        assertNotNull("Set-up logs the first Faculty in", faculty.sessionId);

        User core = core("Phone A", false);
        String fresh = DevData.newPassword();
        repo.resetPassword(faculty, core.id, fresh);
        assertNull(repo.resumeSession(core.sessionId));
        assertEquals(SqliteArenaRepository.END_PASSWORD_RESET,
                repo.getSessionEndReason(core.sessionId));

        User again = repo.login(DevData.CORE_EMAIL, fresh, Role.CORE);
        repo.setUserActive(faculty, again.id, false);
        assertNull(repo.resumeSession(again.sessionId));
        refused(PermissionDeniedException.class, () -> addSport(again, "Kabaddi"));
    }

    @Test
    public void twoLoginsAtTheSameMomentCannotBothSucceed() throws Exception {
        int attempts = 6;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        for (int i = 0; i < attempts; i++) {
            String device = "Phone " + i;
            new Thread(() -> {
                try {
                    start.await();
                    core(device, false);
                    succeeded.incrementAndGet();
                } catch (SessionActiveException e) {
                    refused.incrementAndGet();
                } catch (Exception e) {
                    // counted as neither: the assertions below then fail
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(1, succeeded.get());
        assertEquals(attempts - 1, refused.get());
    }

    @Test
    public void theDatabaseItselfRefusesASecondLiveSession() {
        User first = core("Phone A", false);
        ContentValues second = new ContentValues();
        second.put("id", "second-session");
        second.put("user_id", first.id);
        second.put("created_at", 1);
        second.put("last_seen_at", 1);
        try {
            helper.getWritableDatabase().insertOrThrow("sessions", null, second);
            fail("The unique index should have refused this row");
        } catch (SQLiteConstraintException expected) {
            // correct
        }
    }
}
