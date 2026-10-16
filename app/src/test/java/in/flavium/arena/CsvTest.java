package in.flavium.arena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import in.flavium.arena.export.Csv;

import org.junit.Test;

/** The text of a downloaded CSV file. Runs on the computer. */
public class CsvTest {

    /** The rows of a file without the marker at its start. */
    private static String body(Csv csv) {
        String text = csv.toString();
        assertTrue("The file starts with the UTF-8 marker", text.startsWith(String.valueOf((char) 0xFEFF)));
        return text.substring(1);
    }

    @Test
    public void plainCellsAreSeparatedByCommasAndRowsByLineBreaks() {
        Csv csv = new Csv().row("Branch", "Gold", "Silver").row("Civil Engineering", 2, 0);
        assertEquals("Branch,Gold,Silver\r\nCivil Engineering,2,0\r\n", body(csv));
        assertEquals(2, csv.rowCount());
    }

    @Test
    public void commasQuotesAndLineBreaksAreKeptInsideOneCell() {
        Csv csv = new Csv().row("Rao, Asha", "The \"A\" team", "one\ntwo", null);
        assertEquals("\"Rao, Asha\",\"The \"\"A\"\" team\",\"one\ntwo\",\r\n", body(csv));
    }

    @Test
    public void aCellCannotBecomeASpreadsheetFormula() {
        Csv csv = new Csv().row("=SUM(A1:A9)", "+91 90000", "@name", "-cmd");
        assertEquals("'=SUM(A1:A9),'+91 90000,'@name,'-cmd\r\n", body(csv));
        // Plain numbers with a sign, such as a score difference, are left alone.
        assertEquals("-1,+3,12.45\r\n", body(new Csv().row("-1", "+3", "12.45")));
    }

    @Test
    public void fileNamesAreBuiltFromTheirPartsWithoutUnsafeCharacters() {
        assertEquals("Computer_Engineering_Year_3_Registrations",
                Csv.fileName("Computer Engineering", "Year 3", "Registrations"));
        assertEquals("Football_Men_s_7_a_side_Standings",
                Csv.fileName("Football", "Men's 7-a-side", "Standings"));
        assertEquals("etc_passwd", Csv.fileName("../../etc/passwd"));
        assertEquals("Export", Csv.fileName("", null, "///"));
    }
}
