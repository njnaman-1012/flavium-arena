package in.flavium.arena;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import in.flavium.arena.scoring.LiveLink;

import org.junit.Test;

/** Which addresses are accepted as a CricHeroes match link. Runs on the computer. */
public class LiveLinkTest {

    @Test
    public void theLinkIsOptional() {
        assertNull(LiveLink.problemWith(null));
        assertNull(LiveLink.problemWith(""));
        assertNull(LiveLink.problemWith("   "));
    }

    @Test
    public void secureCricHeroesMatchLinksAreAccepted() {
        assertNull(LiveLink.problemWith("https://cricheroes.com/scorecard/1234567/some-match"));
        assertNull(LiveLink.problemWith("https://www.cricheroes.com/scorecard/1234567/x"));
        assertNull(LiveLink.problemWith("https://cricheroes.in/scorecard/1234567/x"));
        assertNull(LiveLink.problemWith("  https://cricheroes.com/scorecard/1/x  "));
    }

    @Test
    public void otherAddressesAreRefused() {
        assertNotNull(LiveLink.problemWith("http://cricheroes.com/scorecard/1/x"));    // not secure
        assertNotNull(LiveLink.problemWith("https://example.com/scorecard/1/x"));      // other site
        assertNotNull(LiveLink.problemWith("https://cricheroes.com.evil.example/a"));  // look-alike
        assertNotNull(LiveLink.problemWith("https://notcricheroes.com/scorecard/1"));  // look-alike
        assertNotNull(LiveLink.problemWith("https://cricheroes.com"));                 // no match
        assertNotNull(LiveLink.problemWith("https://cricheroes.com/"));
        assertNotNull(LiveLink.problemWith("cricheroes.com/scorecard/1/x"));           // no https
        assertNotNull(LiveLink.problemWith("javascript:alert(1)"));
        assertNotNull(LiveLink.problemWith("https://cricheroes.com/score card"));
    }
}
