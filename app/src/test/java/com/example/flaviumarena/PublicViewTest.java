package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.flaviumarena.sync.Change;
import com.example.flaviumarena.sync.PublicView;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** What the public may see of a shared row. Runs on the computer. */
public class PublicViewTest {

    private static Change row(String table, String key, Object... columnsAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            values.put((String) columnsAndValues[i], columnsAndValues[i + 1]);
        }
        Change change = new Change(table, key, false, values);
        change.rev = 7;
        change.branch = "branch-1";
        change.sport = "sport-1";
        return change;
    }

    @Test
    public void publicTablesAreCopiedAsTheyAre() {
        Change match = row("matches", "m1", "score_a", 2L, "venue", "Main Ground");
        Change copy = PublicView.of(match);
        assertEquals(match.values, copy.values);
        assertEquals(7, copy.rev);
        assertTrue(copy.complete);
        assertEquals("sport-1", copy.sport);
        assertFalse(PublicView.isSplit("matches"));
        assertFalse(PublicView.isPrivate("matches"));
    }

    @Test
    public void historiesAndAssignmentsHaveNoPublicCopy() {
        for (String table : new String[]{"score_audit", "event_audit", "admin_audit",
                "coach_sports"}) {
            assertTrue(PublicView.isPrivate(table));
            assertNull(table, PublicView.of(row(table, "x", "detail", "something")));
        }
    }

    @Test
    public void anApprovedRegistrationIsPublicWithoutItsPhoneNumber() {
        Change approved = row("registrations", "r1", "name", "Ananya Rao", "status", "APPROVED",
                "contact_phone", "9000000011");
        Change copy = PublicView.of(approved);
        assertFalse(copy.deleted);
        assertEquals("Ananya Rao", copy.values.get("name"));
        assertEquals("", copy.values.get("contact_phone"));
        assertFalse("It is a reduced copy", copy.complete);
        // The complete row is not touched.
        assertEquals("9000000011", approved.values.get("contact_phone"));
    }

    @Test
    public void aWaitingOrRejectedRegistrationIsNotPublicAtAll() {
        for (String status : new String[]{"PENDING", "REJECTED"}) {
            Change copy = PublicView.of(row("registrations", "r1", "name", "Isha Verma",
                    "status", status, "contact_phone", "9000000015"));
            // The public is told to stop seeing it, with nothing of its content.
            assertTrue(status, copy.deleted);
            assertTrue(copy.values.isEmpty());
            assertEquals(7, copy.rev);
        }
    }

    @Test
    public void anAccountIsPublicByNameAndRoleButNeverByEmail() {
        Change copy = PublicView.of(row("users", "u1", "name", "Test Asoo Core", "role",
                "ASOO_CORE", "email", "asoo@flavium.test", "active", 1L));
        assertEquals("Test Asoo Core", copy.values.get("name"));
        assertEquals("ASOO_CORE", copy.values.get("role"));
        assertFalse(String.valueOf(copy.values.get("email")).contains("flavium.test"));
        // The placeholder is different for every account, because emails must be unique.
        Change other = PublicView.of(row("users", "u2", "email", "core@flavium.test"));
        assertFalse(copy.values.get("email").equals(other.values.get("email")));
        assertFalse(copy.complete);
    }

    @Test
    public void aRemovalIsARemovalForThePublicToo() {
        Change removed = new Change("matches", "m1", true, null);
        removed.rev = 9;
        Change copy = PublicView.of(removed);
        assertTrue(copy.deleted);
        assertEquals(9, copy.rev);
    }
}
