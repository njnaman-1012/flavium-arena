package com.example.flaviumarena.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Dates are stored as yyyy-MM-dd text and times as HH:mm text, so they sort correctly. */
public final class DateUtil {

    private static final String DATE_PATTERN = "yyyy-MM-dd";

    private DateUtil() {
    }

    public static String today() {
        return daysFromToday(0);
    }

    public static String daysFromToday(int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, days);
        return new SimpleDateFormat(DATE_PATTERN, Locale.US).format(calendar.getTime());
    }

    public static boolean isValidDate(String text) {
        if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return false;
        }
        SimpleDateFormat format = new SimpleDateFormat(DATE_PATTERN, Locale.US);
        format.setLenient(false);
        try {
            format.parse(text);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    public static boolean isValidTime(String text) {
        return text != null && text.matches("([01]\\d|2[0-3]):[0-5]\\d");
    }

    public static String formatTimestamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }
}
