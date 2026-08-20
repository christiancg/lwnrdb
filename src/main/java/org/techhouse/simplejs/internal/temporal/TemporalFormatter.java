package org.techhouse.simplejs.internal.temporal;

import org.techhouse.simplejs.exceptions.RangeErrorException;

/**
 * Canonical string serializers for the ISO 8601 fields/duration records. Rounding is owned by
 * {@link DurationMath}; this class only renders already-computed field values, handling
 * digit-count/truncation display concerns ({@code fractionalSecondDigits}) and the
 * calendar/time-zone annotation options.
 */
public final class TemporalFormatter {
    public enum CalendarName {
        AUTO, ALWAYS, NEVER, CRITICAL;

        public static CalendarName parse(String value) {
            return switch (value) {
                case "auto" -> AUTO;
                case "always" -> ALWAYS;
                case "never" -> NEVER;
                case "critical" -> CRITICAL;
                default -> throw new RangeErrorException("Invalid calendarName option: " + value);
            };
        }
    }

    public enum TimeZoneNameOption {
        AUTO, NEVER, CRITICAL;

        public static TimeZoneNameOption parse(String value) {
            return switch (value) {
                case "auto" -> AUTO;
                case "never" -> NEVER;
                case "critical" -> CRITICAL;
                default -> throw new RangeErrorException("Invalid timeZoneName option: " + value);
            };
        }
    }

    public enum OffsetOption {
        AUTO, NEVER;

        public static OffsetOption parse(String value) {
            return switch (value) {
                case "auto" -> AUTO;
                case "never" -> NEVER;
                default -> throw new RangeErrorException("Invalid offset option: " + value);
            };
        }
    }

    private TemporalFormatter() {
    }

    public static String formatDate(Iso8601Fields date) {
        return formatYear(date.year()) + "-" + pad2(date.month()) + "-" + pad2(date.day());
    }

    public static String formatDate(Iso8601Fields date, CalendarName calendarName) {
        return formatDate(date) + formatCalendarAnnotation(calendarName);
    }

    public static String formatTime(IsoTimeFields time, Integer fractionalSecondDigits) {
        final var sb = new StringBuilder();
        sb.append(pad2(time.hour())).append(':').append(pad2(time.minute())).append(':').append(pad2(time.second()));
        final var fraction = formatFraction(time.millisecond(), time.microsecond(), time.nanosecond(),
                fractionalSecondDigits);
        if (!fraction.isEmpty()) {
            sb.append('.').append(fraction);
        }
        return sb.toString();
    }

    public static String formatDateTime(Iso8601Fields date, IsoTimeFields time, Integer fractionalSecondDigits,
            CalendarName calendarName) {
        return formatDate(date) + "T" + formatTime(time, fractionalSecondDigits)
                + formatCalendarAnnotation(calendarName);
    }

    public static String formatZonedDateTime(Iso8601Fields date, IsoTimeFields time, Integer fractionalSecondDigits,
            String offset, String timeZoneId, TimeZoneNameOption timeZoneOption, OffsetOption offsetOption,
            CalendarName calendarName) {
        final var sb = new StringBuilder();
        sb.append(formatDate(date)).append('T').append(formatTime(time, fractionalSecondDigits));
        if (offsetOption != OffsetOption.NEVER && offset != null) {
            sb.append(offset);
        }
        if (timeZoneOption != TimeZoneNameOption.NEVER && timeZoneId != null) {
            sb.append('[');
            if (timeZoneOption == TimeZoneNameOption.CRITICAL) {
                sb.append('!');
            }
            sb.append(timeZoneId).append(']');
        }
        sb.append(formatCalendarAnnotation(calendarName));
        return sb.toString();
    }

    public static String formatDuration(DurationFields duration) {
        final var sign = DurationMath.sign(duration);
        if (sign == 0) {
            return "PT0S";
        }
        final var sb = new StringBuilder();
        if (sign < 0) {
            sb.append('-');
        }
        sb.append('P');
        appendDateComponent(sb, duration.years(), 'Y');
        appendDateComponent(sb, duration.months(), 'M');
        appendDateComponent(sb, duration.weeks(), 'W');
        appendDateComponent(sb, duration.days(), 'D');

        final var timeNanos = combineSecondsFraction(Math.abs(duration.seconds()), Math.abs(duration.milliseconds()),
                Math.abs(duration.microseconds()), Math.abs(duration.nanoseconds()));
        final var hasTime = duration.hours() != 0 || duration.minutes() != 0 || timeNanos[0] != 0 || timeNanos[1] != 0;
        if (hasTime) {
            sb.append('T');
            appendDateComponent(sb, duration.hours(), 'H');
            appendDateComponent(sb, duration.minutes(), 'M');
            if (timeNanos[0] != 0 || timeNanos[1] != 0) {
                sb.append(timeNanos[0]);
                final var fraction = trimTrailingZeros(zeroPad9(timeNanos[1]));
                if (!fraction.isEmpty()) {
                    sb.append('.').append(fraction);
                }
                sb.append('S');
            }
        }
        return sb.toString();
    }

    private static void appendDateComponent(StringBuilder sb, double value, char designator) {
        if (value == 0) {
            return;
        }
        final var absValue = Math.abs(value);
        if (absValue == Math.floor(absValue) && !Double.isInfinite(absValue)) {
            sb.append((long) absValue);
        } else {
            sb.append(absValue);
        }
        sb.append(designator);
    }

    private static long[] combineSecondsFraction(double seconds, double milliseconds, double microseconds,
            double nanoseconds) {
        final var totalNanos = Math.round(milliseconds * 1_000_000L + microseconds * 1_000L + nanoseconds);
        final var wholeSeconds = (long) seconds + Math.floorDiv(totalNanos, 1_000_000_000L);
        final var fractionNanos = Math.floorMod(totalNanos, 1_000_000_000L);
        return new long[]{wholeSeconds, fractionNanos};
    }

    private static String formatFraction(int millisecond, int microsecond, int nanosecond,
            Integer fractionalSecondDigits) {
        final var nanos = millisecond * 1_000_000 + microsecond * 1_000 + nanosecond;
        final var full = zeroPad9(nanos);
        if (fractionalSecondDigits == null) {
            return trimTrailingZeros(full);
        }
        if (fractionalSecondDigits == 0) {
            return "";
        }
        return full.substring(0, fractionalSecondDigits);
    }

    private static String zeroPad9(long nanos) {
        final var digits = Long.toString(nanos);
        return "0".repeat(9 - digits.length()) + digits;
    }

    private static String trimTrailingZeros(String digits) {
        var end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
        }
        return digits.substring(0, end);
    }

    private static String formatCalendarAnnotation(CalendarName calendarName) {
        return switch (calendarName) {
            case NEVER, AUTO -> "";
            case ALWAYS -> "[u-ca=iso8601]";
            case CRITICAL -> "[!u-ca=iso8601]";
        };
    }

    private static String formatYear(int year) {
        if (year >= 0 && year <= 9999) {
            return pad(year, 4);
        }
        return (year < 0 ? "-" : "+") + pad(Math.abs(year), 6);
    }

    private static String pad2(int value) {
        return pad(value, 2);
    }

    private static String pad(int value, int width) {
        final var digits = Integer.toString(value);
        return digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
    }
}
