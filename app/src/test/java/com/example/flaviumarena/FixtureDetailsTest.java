package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.ui.common.Ui;

import org.junit.Test;

import java.util.Arrays;

/**
 * Who scores a fixture and when it was last changed is organiser information: shown to
 * Faculty, Cores, Asoo Cores and the Coach of the sport, and to nobody else. Runs on the
 * computer.
 */
public class FixtureDetailsTest {

    private static User user(String role) {
        User u = new User();
        u.id = "u-" + role;
        u.role = role;
        return u;
    }

    private static String details(boolean organiser) {
        return Ui.fixtureDetails("Football, Men's 7-a-side", "League Round 1", "2026-10-12 16:00",
                "Main Ground", "Test Asoo Core", 0L, "Test Core", organiser);
    }

    @Test
    public void everyoneElseSeesTheFixtureWithoutOrganiserLines() {
        assertEquals("Football, Men's 7-a-side\nRound: League Round 1\nWhen: 2026-10-12 16:00\n"
                + "Venue: Main Ground", details(false));
    }

    @Test
    public void organisersAlsoSeeTheScorerAndTheLastChange() {
        String text = details(true);
        assertTrue(text.startsWith(details(false) + "\nScorer: Test Asoo Core\nLast updated: "));
        assertTrue(text.endsWith(" by Test Core"));
    }

    @Test
    public void aVenueNotYetKnownIsSaidSo() {
        assertTrue(Ui.fixtureDetails("Chess, Open", "Round 1", "2026-10-12 ", "", "", 0L, "",
                false).endsWith("Venue: to be announced"));
    }

    @Test
    public void onlyOrganisersCountAsOrganisersForFixtureDetails() {
        // The public and Branch CRs: no.
        assertFalse(AccessControl.canViewScoreHistory(null));
        assertFalse(AccessControl.canViewScoreHistory(user(Role.CAPTAIN)));
        // Faculty, Core, Asoo Core: yes.
        assertTrue(AccessControl.canViewScoreHistory(user(Role.FACULTY)));
        assertTrue(AccessControl.canViewScoreHistory(user(Role.CORE)));
        assertTrue(AccessControl.canViewScoreHistory(user(Role.ASOO_CORE)));
        // A Coach: in the assigned sport only.
        User coach = user(Role.COACH);
        coach.coachSportIds = Arrays.asList("football");
        assertTrue(AccessControl.canViewScoreHistory(AccessControl.inSport(coach, "football")));
        assertFalse(AccessControl.canViewScoreHistory(AccessControl.inSport(coach, "chess")));
        // A deactivated organiser counts as the public.
        User former = user(Role.CORE);
        former.active = false;
        assertFalse(AccessControl.canViewScoreHistory(former));
    }
}
