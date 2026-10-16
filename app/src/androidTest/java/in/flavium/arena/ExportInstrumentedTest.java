package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import in.flavium.arena.data.ArenaRepository;
import in.flavium.arena.data.DbHelper;
import in.flavium.arena.data.DevData;
import in.flavium.arena.data.SqliteArenaRepository;
import in.flavium.arena.data.ValidationException;
import in.flavium.arena.export.ExportFile;
import in.flavium.arena.export.ExportService;
import in.flavium.arena.model.Branch;
import in.flavium.arena.model.Role;
import in.flavium.arena.model.User;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Downloads contain only what the person who asks may see. The development data has
 * approved and pending registrations with phone numbers in several branches, which is
 * what these checks look for. Uses a temporary in-memory database.
 */
@RunWith(AndroidJUnit4.class)
public class ExportInstrumentedTest {

    /** Phone numbers of the development registrations all start like this. */
    private static final String PHONE = "90000000";

    private DbHelper helper;
    private ArenaRepository repo;
    private User core, cr;
    private String ownBranch, otherBranch;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null, true);
        repo = new SqliteArenaRepository(helper);
        core = repo.login(DevData.CORE_EMAIL, DevData.CORE_PASSWORD, Role.CORE);
        cr = repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CAPTAIN);
        ownBranch = cr.branchId;
        // Civil Engineering has a pending football team in the development data.
        for (Branch b : repo.getBranches()) {
            if (b.name.equals("Civil Engineering")) {
                otherBranch = b.id;
            }
        }
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private static String text(ExportFile file) {
        return file.text();
    }

    @Test
    public void branchCrGetsFullRecordsOfTheirOwnBranchOnly() {
        ExportService exports = new ExportService(repo, cr);
        ExportFile own = exports.branchRegistrations(ownBranch);
        assertEquals("Computer_Engineering_Registrations.csv", own.fileName);
        assertEquals(ExportFile.TYPE_CSV, own.mimeType);
        assertTrue(text(own).contains("Contact phone"));
        assertTrue(text(own).contains(PHONE));
        assertTrue("Their own pending entry is included", text(own).contains("PENDING"));

        // Asking for another branch gives what the public gets: no phone numbers and
        // no registration that is still waiting for approval.
        ExportFile other = exports.branchRegistrations(otherBranch);
        assertFalse(text(other).contains(PHONE));
        assertFalse(text(other).contains("Contact phone"));
        assertFalse(text(other).contains("PENDING"));
        assertFalse(text(other).contains("Neil Roy"));
    }

    @Test
    public void publicDownloadsHaveNoPrivateDetails() {
        ExportService exports = new ExportService(repo, null);
        String all = text(exports.allRegistrations()) + text(exports.branchRegistrations(ownBranch))
                + text(exports.branchList()) + text(exports.fixtures(null))
                + text(exports.medalTable()) + text(exports.branchFixtures(ownBranch))
                + text(exports.branchMedals(ownBranch));
        assertFalse(all.contains(PHONE));
        assertFalse(all.contains("PENDING"));
        // Account details are never part of a download.
        assertFalse(all.contains("@flavium.test"));
        assertFalse(all.contains("Branch CR"));
        assertFalse(all.toLowerCase().contains("password"));
        assertFalse(all.contains("pbkdf2"));
        // Approved entries and fixtures are public.
        assertTrue(all.contains("Ananya Rao"));
        assertTrue(all.contains("Main Ground"));
    }

    @Test
    public void aSessionThatWasSignedOutExportsOnlyPublicData() {
        // The same account logs in elsewhere and takes the session over.
        repo.login(DevData.CAPTAIN_EMAIL, DevData.CAPTAIN_PASSWORD, Role.CAPTAIN, "Other", true);
        String own = text(new ExportService(repo, cr).branchRegistrations(ownBranch));
        assertFalse(own.contains(PHONE));
        assertFalse(own.contains("PENDING"));
    }

    @Test
    public void organisersGetCompleteReportsWithoutAccountSecrets() {
        ExportService exports = new ExportService(repo, core);
        String registrations = text(exports.allRegistrations());
        assertTrue(registrations.contains(PHONE));
        assertTrue(registrations.contains("PENDING"));
        assertTrue(registrations.contains("Year 3"));
        assertTrue(registrations.contains("Football"));

        String branches = text(exports.branchList());
        assertTrue("An organiser sees who the Branch CR is", branches.contains("Branch CR"));
        assertTrue(branches.contains("Test Captain"));
        assertTrue(branches.contains("Gold"));
        assertFalse("There are no medal points", branches.toLowerCase().contains("point"));
        assertFalse(text(exports.medalTable()).toLowerCase().contains("point"));

        String everything = registrations + branches + text(exports.fixtures(null));
        assertFalse(everything.contains("pbkdf2"));
        assertFalse(everything.toLowerCase().contains("password"));
        assertFalse(everything.contains("@flavium.test"));
    }

    @Test
    public void standingsFixturesAndReportsAreProduced() {
        ExportService exports = new ExportService(repo, null);
        String sevens = null;
        for (in.flavium.arena.model.Sport s : repo.getSports()) {
            for (in.flavium.arena.model.Category c : repo.getCategories(s.id)) {
                if (c.name.equals("Men's 7-a-side")) {
                    sevens = c.id;
                }
            }
        }
        ExportFile standings = exports.categoryStandings(sevens);
        assertEquals("Football_Men_s_7_a_side_Standings.csv", standings.fileName);
        // League points stay in the table of a category.
        assertTrue(text(standings).contains("League points"));
        assertTrue(text(standings).contains("Computer Engineering — Year 3"));
        assertTrue(text(exports.fixtures(sevens)).contains("League Round 1"));
        assertTrue(text(exports.branchFixtures(ownBranch)).contains("Won"));

        // The PDF reports are real PDF files.
        ExportFile report = exports.branchReport(ownBranch);
        assertEquals("Computer_Engineering_Report.pdf", report.fileName);
        assertTrue(report.isPdf());
        assertEquals("%PDF", new String(report.content, 0, 4));
        assertEquals("%PDF", new String(exports.festivalReport().content, 0, 4));

        try {
            exports.branchRegistrations("no-such-branch");
            fail("An unknown branch must be refused");
        } catch (ValidationException expected) {
            // correct
        }
    }
}
