package org.techhouse.simplejs.internal.temporal;

public record DurationFields(double years, double months, double weeks, double days, double hours, double minutes,
        double seconds, double milliseconds, double microseconds, double nanoseconds) {
    public static final DurationFields ZERO = new DurationFields(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
