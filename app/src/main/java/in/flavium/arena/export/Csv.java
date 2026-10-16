package in.flavium.arena.export;

/**
 * Builds the text of a CSV file (rows of cells separated by commas) that opens correctly
 * in Excel and Google Sheets. Pure Java, covered by unit tests.
 */
public final class Csv {

    /** Tells Excel that the file is UTF-8, so names with accents are shown correctly. */
    private static final String BYTE_ORDER_MARK = String.valueOf((char) 0xFEFF);

    private final StringBuilder text = new StringBuilder(BYTE_ORDER_MARK);
    private int rows;

    /** Adds one row. Null cells become empty; numbers are written as they are. */
    public Csv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(cell(cells[i] == null ? "" : String.valueOf(cells[i])));
        }
        text.append("\r\n");
        rows++;
        return this;
    }

    public int rowCount() {
        return rows;
    }

    @Override
    public String toString() {
        return text.toString();
    }

    /**
     * One cell, made safe:
     *  - a cell with a comma, a quote or a line break is put in quotes, and quotes
     *    inside it are doubled, as the CSV format requires;
     *  - a cell that starts with = + - or @ gets an apostrophe in front, so that a
     *    spreadsheet shows it as text instead of running it as a formula. Someone could
     *    otherwise type a formula as a team member's name.
     */
    static String cell(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !isNumber(v)) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** "-1" and "+3" are numbers (a score difference), not formulas. */
    private static boolean isNumber(String value) {
        return value.matches("[+-]?\\d+(\\.\\d+)?");
    }

    /**
     * A file name from several parts, for example ("Computer Engineering", "Year 3",
     * "Registrations") gives "Computer_Engineering_Year_3_Registrations". Characters
     * that are not allowed in file names are left out.
     */
    public static String fileName(String... parts) {
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            String clean = part == null ? "" : part.replaceAll("[^A-Za-z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
            if (clean.isEmpty()) {
                continue;
            }
            if (name.length() > 0) {
                name.append('_');
            }
            name.append(clean);
        }
        return name.length() == 0 ? "Export" : name.toString();
    }
}
