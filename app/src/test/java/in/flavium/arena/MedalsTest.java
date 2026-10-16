package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import in.flavium.arena.model.Branch;
import in.flavium.arena.model.MedalRow;
import in.flavium.arena.model.MedalWin;
import in.flavium.arena.scoring.Medals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The rules of the medal table. Runs on the computer. */
public class MedalsTest {

    private static Branch branch(String id, String name, boolean active) {
        Branch b = new Branch();
        b.id = id;
        b.name = name;
        b.code = id.toUpperCase();
        b.active = active;
        return b;
    }

    private static MedalWin win(String branchId, String medal) {
        MedalWin w = new MedalWin();
        w.branchId = branchId;
        w.medal = medal;
        return w;
    }

    @Test
    public void theCategoryRuleDecidesHowManyMedalsThereAre() {
        assertEquals(3, Medals.countFor("Gold, Silver and Bronze"));
        assertEquals(2, Medals.countFor("Gold and Silver"));
        assertEquals(1, Medals.countFor("Gold only"));
        assertEquals(0, Medals.countFor("No medals"));
        // A category from before the rule existed awards all three.
        assertEquals(3, Medals.countFor(""));
        assertEquals(3, Medals.countFor(null));
    }

    @Test
    public void positionsBecomeMedalsOnlyAsFarAsTheRuleAllows() {
        assertEquals(Medals.GOLD, Medals.forPosition(1, 3));
        assertEquals(Medals.SILVER, Medals.forPosition(2, 3));
        assertEquals(Medals.BRONZE, Medals.forPosition(3, 3));
        assertNull(Medals.forPosition(4, 3));
        assertNull(Medals.forPosition(3, 2));
        assertNull(Medals.forPosition(2, 1));
        assertNull(Medals.forPosition(1, 0));
        assertNull(Medals.forPosition(0, 3));
    }

    @Test
    public void onlyTheFinalAndTheThirdPlaceMatchDecideKnockoutMedals() {
        assertTrue(Medals.isFinalRound("Final"));
        assertTrue(Medals.isFinalRound("  final "));
        assertFalse(Medals.isFinalRound("Semi-final"));
        assertFalse(Medals.isFinalRound("Quarter final"));
        assertFalse(Medals.isFinalRound("League Round 1"));
        assertFalse(Medals.isFinalRound(null));

        assertTrue(Medals.isThirdPlaceRound("Third place"));
        assertTrue(Medals.isThirdPlaceRound("3rd place play-off"));
        assertTrue(Medals.isThirdPlaceRound("Bronze medal match"));
        assertFalse(Medals.isThirdPlaceRound("Final"));
        assertFalse(Medals.isThirdPlaceRound("Semi-final"));
    }

    @Test
    public void tableIsOrderedByGoldThenSilverThenBronze() {
        List<Branch> branches = Arrays.asList(branch("a", "Alpha", true),
                branch("b", "Beta", true), branch("c", "Civil", true), branch("d", "Delta", true));
        List<MedalWin> wins = new ArrayList<>();
        // Alpha: 1 gold. Beta: 3 silver, 2 bronze. Civil: 1 gold, 1 bronze. Delta: nothing.
        wins.add(win("a", Medals.GOLD));
        wins.add(win("b", Medals.SILVER));
        wins.add(win("b", Medals.SILVER));
        wins.add(win("b", Medals.SILVER));
        wins.add(win("b", Medals.BRONZE));
        wins.add(win("b", Medals.BRONZE));
        wins.add(win("c", Medals.GOLD));
        wins.add(win("c", Medals.BRONZE));

        List<MedalRow> table = Medals.table(wins, branches);
        assertEquals(4, table.size());
        // One gold beats any number of silver: there are no points to add up.
        assertEquals("Civil", table.get(0).branchName);
        assertEquals("Alpha", table.get(1).branchName);
        assertEquals("Beta", table.get(2).branchName);
        assertEquals("Delta", table.get(3).branchName);
        assertEquals(5, table.get(2).total());
        assertEquals(0, table.get(3).total());
        assertEquals(1, table.get(0).rank);
        assertEquals(2, table.get(1).rank);
        assertEquals(4, table.get(3).rank);
    }

    @Test
    public void branchesWithTheSameMedalsShareARank() {
        List<Branch> branches = Arrays.asList(branch("a", "Alpha", true),
                branch("b", "Beta", true), branch("c", "Civil", true));
        List<MedalWin> wins = Arrays.asList(win("b", Medals.GOLD), win("a", Medals.GOLD),
                win("c", Medals.SILVER));
        List<MedalRow> table = Medals.table(wins, branches);
        assertEquals("Alpha", table.get(0).branchName); // level: by name
        assertEquals(1, table.get(0).rank);
        assertEquals(1, table.get(1).rank);
        assertEquals(3, table.get(2).rank);
    }

    @Test
    public void medalsWithoutAnActiveBranchAreNotCounted() {
        List<Branch> branches = Arrays.asList(branch("a", "Alpha", true),
                branch("x", "Unassigned", false));
        List<MedalWin> wins = Arrays.asList(win("x", Medals.GOLD), win(null, Medals.GOLD),
                win("gone", Medals.SILVER), win("a", Medals.BRONZE));
        List<MedalRow> table = Medals.table(wins, branches);
        assertEquals(1, table.size());
        assertEquals(0, table.get(0).gold);
        assertEquals(1, table.get(0).bronze);
    }
}
