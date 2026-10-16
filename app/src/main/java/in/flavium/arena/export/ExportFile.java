package in.flavium.arena.export;

import java.nio.charset.StandardCharsets;

/** A finished download: its name, its type and its bytes. */
public class ExportFile {

    public static final String TYPE_CSV = "text/csv";
    public static final String TYPE_PDF = "application/pdf";

    public final String fileName;
    public final String mimeType;
    public final byte[] content;

    public ExportFile(String fileName, String mimeType, byte[] content) {
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.content = content;
    }

    static ExportFile csv(String name, Csv csv) {
        return new ExportFile(name + ".csv", TYPE_CSV,
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    public boolean isPdf() {
        return TYPE_PDF.equals(mimeType);
    }

    /** The content as text. Only meaningful for a CSV file. */
    public String text() {
        return new String(content, StandardCharsets.UTF_8);
    }
}
