package org.techhouse.simplejs.values;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * A JavaScript {@code Date} value backed by an epoch-millis {@code double}. {@code NaN} models an
 * invalid date. All component access is in UTC (the sandbox has no local time zone).
 */
public final class JsDate extends JsValue {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private double time;

    public JsDate(double time) {
        this.time = time;
    }

    public double getTime() {
        return time;
    }

    public void setTime(double time) {
        this.time = time;
    }

    public boolean isValid() {
        return !Double.isNaN(time);
    }

    public ZonedDateTime atUtc() {
        return Instant.ofEpochMilli((long) time).atZone(ZoneOffset.UTC);
    }

    public String toISOString() {
        return isValid() ? ISO.format(Instant.ofEpochMilli((long) time)) : null;
    }

    public String toDateString() {
        if (!isValid()) {
            return "Invalid Date";
        }
        final var zoned = atUtc();
        final var day = zoned.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US);
        final var month = zoned.getMonth().getDisplayName(TextStyle.SHORT, Locale.US);
        return String.format(Locale.US, "%s %s %02d %04d %02d:%02d:%02d GMT+0000 (Coordinated Universal Time)", day,
                month, zoned.getDayOfMonth(), zoned.getYear(), zoned.getHour(), zoned.getMinute(), zoned.getSecond());
    }
}
