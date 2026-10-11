package com.example.flaviumarena.scoring;

import java.util.Locale;

/**
 * Reading and writing times and distances.
 *
 * Times are stored as whole thousandths of a second and distances as whole centimetres,
 * so results are compared exactly and never suffer from rounding of decimal fractions.
 */
public final class Measure {

    /** Returned by the parse methods when the text is not a valid value. */
    public static final int INVALID = -1;

    private Measure() {
    }

    /**
     * "12.34" (seconds) or "1:02.35" (minutes:seconds) to thousandths of a second.
     * Returns INVALID for anything else, and for zero.
     */
    public static int parseTimeMs(String text) {
        if (text == null) {
            return INVALID;
        }
        String t = text.trim().replace(',', '.');
        if (!t.matches("(\\d{1,3}:)?\\d{1,4}(\\.\\d{1,3})?")) {
            return INVALID;
        }
        long minutes = 0;
        if (t.contains(":")) {
            minutes = Long.parseLong(t.substring(0, t.indexOf(':')));
            t = t.substring(t.indexOf(':') + 1);
            // After a colon the seconds must be below 60.
            if (Double.parseDouble(t) >= 60) {
                return INVALID;
            }
        }
        String whole = t.contains(".") ? t.substring(0, t.indexOf('.')) : t;
        String fraction = t.contains(".") ? t.substring(t.indexOf('.') + 1) : "";
        while (fraction.length() < 3) {
            fraction += "0";
        }
        long ms = (minutes * 60 + Long.parseLong(whole)) * 1000 + Long.parseLong(fraction);
        return ms <= 0 || ms > Integer.MAX_VALUE ? INVALID : (int) ms;
    }

    /** 12340 to "12.34"; 62350 to "1:02.35". Hundredths are shown, as on a stopwatch. */
    public static String formatTime(int ms) {
        int minutes = ms / 60000;
        int rest = ms % 60000;
        String seconds = String.format(Locale.US, "%d.%02d", rest / 1000, (rest % 1000) / 10);
        if (minutes == 0) {
            return seconds;
        }
        // Two digits for the seconds after a colon: 1:02.35
        return minutes + ":" + (rest < 10000 ? "0" : "") + seconds;
    }

    /** "12.45" (metres) to centimetres. Returns INVALID for anything else, and for zero. */
    public static int parseDistanceCm(String text) {
        if (text == null) {
            return INVALID;
        }
        String t = text.trim().replace(',', '.');
        if (!t.matches("\\d{1,3}(\\.\\d{1,2})?")) {
            return INVALID;
        }
        String whole = t.contains(".") ? t.substring(0, t.indexOf('.')) : t;
        String fraction = t.contains(".") ? t.substring(t.indexOf('.') + 1) : "";
        while (fraction.length() < 2) {
            fraction += "0";
        }
        int cm = Integer.parseInt(whole) * 100 + Integer.parseInt(fraction);
        return cm <= 0 ? INVALID : cm;
    }

    /** 1245 to "12.45". */
    public static String formatDistance(int cm) {
        return String.format(Locale.US, "%d.%02d", cm / 100, cm % 100);
    }
}
