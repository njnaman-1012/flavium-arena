package in.flavium.arena.export;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Writes a plain, readable PDF report: a title, headings and lines of text on A4 pages.
 * Long lines are wrapped and a new page is started when the current one is full.
 */
class PdfReport {

    // A4 in PDF points (1/72 inch).
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 48;

    private final PdfDocument document = new PdfDocument();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private PdfDocument.Page page;
    private Canvas canvas;
    private int pageNumber;
    private float y;

    PdfReport(String title, String subtitle) {
        newPage();
        write(title, 20, true, 0xFF1B1B2B, 6);
        if (subtitle != null && !subtitle.isEmpty()) {
            write(subtitle, 10, false, 0xFF6E6E80, 12);
        }
    }

    void heading(String text) {
        y += 8;
        write(text, 13, true, 0xFF4B35D9, 4);
    }

    void line(String text) {
        write(text, 10, false, 0xFF1B1B2B, 2);
    }

    /** A line for an empty section, in grey. */
    void note(String text) {
        write(text, 10, false, 0xFF6E6E80, 2);
    }

    byte[] finish() throws IOException {
        document.finishPage(page);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.writeTo(out);
        document.close();
        return out.toByteArray();
    }

    private void newPage() {
        if (page != null) {
            document.finishPage(page);
        }
        pageNumber++;
        page = document.startPage(
                new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create());
        canvas = page.getCanvas();
        y = MARGIN;
    }

    private void write(String text, float size, boolean bold, int colour, float gapAfter) {
        paint.setTextSize(size);
        paint.setColor(colour);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        float lineHeight = size * 1.45f;
        float width = PAGE_WIDTH - 2 * MARGIN;

        for (String paragraph : text.split("\n")) {
            String rest = paragraph;
            do {
                // As many characters as fit on the line, broken at a space when possible.
                int count = paint.breakText(rest, true, width, null);
                if (count < rest.length()) {
                    int space = rest.lastIndexOf(' ', count);
                    if (space > 0) {
                        count = space;
                    }
                }
                if (y + lineHeight > PAGE_HEIGHT - MARGIN) {
                    newPage();
                }
                y += lineHeight;
                canvas.drawText(rest.substring(0, count), MARGIN, y, paint);
                rest = rest.substring(count).trim();
            } while (!rest.isEmpty());
        }
        y += gapAfter;
    }
}
