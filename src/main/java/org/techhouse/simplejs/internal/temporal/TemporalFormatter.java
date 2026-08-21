package org.techhouse.simplejs.internal.temporal;

import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.Locale;
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

    // TemporalYearMonthToString: the "iso8601" calendar never appends the reference day, unlike a
    // non-ISO calendar (out of scope for this engine - see the feature plan's scope-defining finding).
    public static String formatYearMonth(Iso8601Fields yearMonth) {
        return formatYear(yearMonth.year()) + "-" + pad2(yearMonth.month());
    }

    // TemporalYearMonthToString: the reference ISO day is only shown when the calendar annotation
    // is forced on (showCalendar "always"/"critical") - otherwise it stays hidden, mirroring
    // formatMonthDay's symmetric treatment of the reference year.
    public static String formatYearMonth(Iso8601Fields yearMonth, CalendarName calendarName) {
        final var withDay = calendarName == CalendarName.ALWAYS || calendarName == CalendarName.CRITICAL
                ? formatYearMonth(yearMonth) + "-" + pad2(yearMonth.day())
                : formatYearMonth(yearMonth);
        return withDay + formatCalendarAnnotation(calendarName);
    }

    // TemporalMonthDayToString: the reference year is only shown when the calendar annotation is
    // forced on (showCalendar "always"/"critical") - otherwise (including "never") it stays hidden,
    // since for the "iso8601" calendar the year plays no role in round-tripping a bare month-day.
    public static String formatMonthDay(Iso8601Fields monthDay, CalendarName calendarName) {
        final var monthDayText = pad2(monthDay.month()) + "-" + pad2(monthDay.day());
        final var withYear = calendarName == CalendarName.ALWAYS || calendarName == CalendarName.CRITICAL
                ? formatYear(monthDay.year()) + "-" + monthDayText
                : monthDayText;
        return withYear + formatCalendarAnnotation(calendarName);
    }

    // A "minute" smallestUnit/rounding target omits the seconds field entirely (rather than
    // formatting ":00"), per the Instant/PlainTime toString grammar.
    public static String formatTimeMinutePrecision(IsoTimeFields time) {
        return pad2(time.hour()) + ":" + pad2(time.minute());
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
        return formatDuration(duration, null);
    }

    // fractionalSecondDigits forces an exact digit count on the seconds fraction (0..9, or null for
    // the default "trim trailing zeros, omit if empty" behavior) - the same option every other
    // Temporal type's toString accepts, restricted here to the fractional-second units since Duration
    // has no smallestUnit coarser than seconds (day/hour/minute are never truncated away).
    public static String formatDuration(DurationFields duration, Integer fractionalSecondDigits) {
        final var sign = DurationMath.sign(duration);
        // An explicit (non-"auto") precision forces the seconds unit to be shown even at 0 digits
        // (e.g. fractionalSecondDigits: 0 still renders "T0S"), not just a nonzero digit count.
        final var forcesFraction = fractionalSecondDigits != null;
        if (sign == 0 && !forcesFraction) {
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
        final var hasTime = duration.hours() != 0 || duration.minutes() != 0 || timeNanos[0] != 0 || timeNanos[1] != 0
                || forcesFraction;
        if (hasTime) {
            sb.append('T');
            appendDateComponent(sb, duration.hours(), 'H');
            appendDateComponent(sb, duration.minutes(), 'M');
            if (timeNanos[0] != 0 || timeNanos[1] != 0 || fractionalSecondDigits != null) {
                sb.append(timeNanos[0]);
                final var fraction = fractionalSecondDigits == null
                        ? trimTrailingZeros(zeroPad9(timeNanos[1]))
                        : formatFraction(0, 0, (int) timeNanos[1], fractionalSecondDigits);
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

    // Each field can independently be a float64-precision-lossy magnitude far beyond what a single
    // `double` multiplication/Math.round can carry without silently overflowing `long` (Java's
    // double->long narrowing conversion CLAMPS rather than throwing once the double exceeds
    // Long.MAX_VALUE) - see since/until float64-representable-integer.js. BigInteger keeps the
    // combination exact; only the final whole-seconds/fraction split is narrowed back to `long`,
    // which stays in range for every realistic duration since the fractional part is always < 1e9.
    private static long[] combineSecondsFraction(double seconds, double milliseconds, double microseconds,
            double nanoseconds) {
        final var totalNanos = BigInteger.valueOf((long) seconds).multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf((long) milliseconds).multiply(BigInteger.valueOf(1_000_000L)))
                .add(BigInteger.valueOf((long) microseconds).multiply(BigInteger.valueOf(1_000L)))
                .add(BigInteger.valueOf((long) nanoseconds));
        final var divRem = totalNanos.divideAndRemainder(BigInteger.valueOf(1_000_000_000L));
        var wholeSeconds = divRem[0];
        var fractionNanos = divRem[1];
        if (fractionNanos.signum() < 0) {
            fractionNanos = fractionNanos.add(BigInteger.valueOf(1_000_000_000L));
            wholeSeconds = wholeSeconds.subtract(BigInteger.ONE);
        }
        return new long[]{wholeSeconds.longValueExact(), fractionNanos.longValueExact()};
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

    // Shared by every Temporal type that renders a UTC offset (Instant, PlainDateTime, ZonedDateTime),
    // replacing what used to be an identically duplicated private helper in each builtins class.
    public static String formatOffset(ZoneOffset offset) {
        final var totalSeconds = offset.getTotalSeconds();
        final var sign = totalSeconds < 0 ? "-" : "+";
        final var abs = Math.abs(totalSeconds);
        final var hours = abs / 3600;
        final var minutes = (abs % 3600) / 60;
        final var seconds = abs % 60;
        final var base = sign + String.format(Locale.US, "%02d:%02d", hours, minutes);
        return seconds == 0 ? base : base + String.format(Locale.US, ":%02d", seconds);
    }

    public static String formatCalendarAnnotation(CalendarName calendarName) {
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
