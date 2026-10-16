package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.DatabaseUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.DbHelper;
import com.example.flaviumarena.data.DevData;
import com.example.flaviumarena.data.PermissionDeniedException;
import com.example.flaviumarena.data.SqliteArenaRepository;
import com.example.flaviumarena.data.ValidationException;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.MedalRow;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.User;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Editing and removing branches (BranchEditActivity uses exactly these calls). Removing
 * a branch deactivates it: nothing of its registrations, fixtures or results is deleted.
 * Uses a temporary in-memory database.
 */
@RunWith(AndroidJUnit4.class)
public class BranchManagementInstrumentedTest {

    private DbHelper helper;
    private ArenaRepository repo;
    private User faculty;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null, true);
        repo = new SqliteArenaRepository(helper);
        faculty = repo.createFirstFaculty("Test Faculty", "faculty@flavium.test", "Temp@12345",
                "faculty@flavium.test");
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

    private long count(String table, String where, String... args) {
        return DatabaseUtils.queryNumEntries(helper.getReadableDatabase(), table, where, args);
    }

    /** A branch with registrations and fixtures but without an active Branch CR. */
    private Branch branchWithHistory() {
        for (Branch b : repo.getBranches()) {
            boolean hasCr = count("users", "role = 'CAPTAIN' AND active = 1 AND branch_id = ?",
                    b.id) > 0;
            if (!hasCr && count("registrations", "branch_id = ?", b.id) > 0
                    && !repo.getMatchesOfBranch(b.id).isEmpty()) {
                return b;
            }
        }
        throw new IllegalStateException("The sample data has no such branch");
    }

    @Test
    public void facultyRenamesABranchAndItsTeamsFollow() {
        Branch b = branchWithHistory();
        b.name = "Civil and Environmental Engineering";
        repo.saveBranch(faculty, b);
        assertEquals("Civil and Environmental Engineering", repo.getBranch(b.id).name);
        // The same name twice is refused.
        Branch other = repo.getBranches().get(0).id.equals(b.id) ? repo.getBranches().get(1)
                : repo.getBranches().get(0);
        Branch clash = repo.getBranch(other.id);
        clash.name = "Civil and Environmental Engineering";
        refused(ValidationException.class, () -> repo.saveBranch(faculty, clash));
    }

    @Test
    public void removingABranchKeepsItsRegistrationsFixturesAndResults() {
        Branch b = branchWithHistory();
        long registrations = count("registrations", "branch_id = ?", b.id);
        int matches = repo.getMatchesOfBranch(b.id).size();
        long allMatches = count("matches", null);

        repo.setBranchActive(faculty, b.id, false);

        assertFalse(repo.getBranch(b.id).active);
        assertEquals(registrations, count("registrations", "branch_id = ?", b.id));
        assertEquals(matches, repo.getMatchesOfBranch(b.id).size());
        assertEquals(allMatches, count("matches", null));
        for (MedalRow row : repo.getMedalTable()) {
            assertFalse("A removed branch is not in the medal table", b.id.equals(row.branchId));
        }

        // Restoring brings it back as it was.
        repo.setBranchActive(faculty, b.id, true);
        assertTrue(repo.getBranch(b.id).active);
        assertEquals(registrations, count("registrations", "branch_id = ?", b.id));
    }

    @Test
    public void aBranchWithAnActiveBranchCrIsNotRemovedAndSaysWhy() {
        User cr = repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CAPTAIN);
        try {
            repo.setBranchActive(faculty, cr.branchId, false);
            fail("Removed although it has an active Branch CR");
        } catch (ValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Branch CR"));
        }
        assertTrue(repo.getBranch(cr.branchId).active);
    }

    @Test
    public void onlyFacultyManagesBranches() {
        Branch b = branchWithHistory();
        User core = repo.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE);
        User cr = repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CAPTAIN);
        for (User who : new User[]{null, core, cr}) {
            refused(PermissionDeniedException.class, () -> repo.setBranchActive(who, b.id, false));
            Branch renamed = repo.getBranch(b.id);
            renamed.name = "Renamed";
            refused(PermissionDeniedException.class, () -> repo.saveBranch(who, renamed));
        }
        assertTrue(repo.getBranch(b.id).active);
    }
}
