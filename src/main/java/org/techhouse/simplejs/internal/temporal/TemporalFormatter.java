package org.techhouse.simplejs.internal.temporal;

import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.Locale;
import org.techhouse.simplejs.exceptions.RangeErrorException;

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

    public static String formatYearMonth(Iso8601Fields yearMonth) {
        return formatYear(yearMonth.year()) + "-" + pad2(yearMonth.month());
    }

    public static String formatYearMonth(Iso8601Fields yearMonth, CalendarName calendarName) {
        final var withDay = calendarName == CalendarName.ALWAYS || calendarName == CalendarName.CRITICAL
                ? formatYearMonth(yearMonth) + "-" + pad2(yearMonth.day())
                : formatYearMonth(yearMonth);
        return withDay + formatCalendarAnnotation(calendarName);
    }

    public static String formatMonthDay(Iso8601Fields monthDay, CalendarName calendarName) {
        final var monthDayText = pad2(monthDay.month()) + "-" + pad2(monthDay.day());
        final var withYear = calendarName == CalendarName.ALWAYS || calendarName == CalendarName.CRITICAL
                ? formatYear(monthDay.year()) + "-" + monthDayText
                : monthDayText;
        return withYear + formatCalendarAnnotation(calendarName);
    }

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

    public static String formatDuration(DurationFields duration, Integer fractionalSecondDigits) {
        final var sign = DurationMath.sign(duration);
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

    private static long[] combineSecondsFraction(double seconds, double milliseconds, double microseconds,
            double nanoseconds) {
        final var totalNanos = wholeNanos(milliseconds).multiply(BigInteger.valueOf(1_000_000L))
                .add(wholeNanos(microseconds).multiply(BigInteger.valueOf(1_000L))).add(wholeNanos(nanoseconds));
        final var dm = totalNanos.divideAndRemainder(BigInteger.valueOf(1_000_000_000L));
        final var wholeSeconds = wholeNanos(seconds).add(dm[0]);
        return new long[]{wholeSeconds.longValueExact(), dm[1].longValueExact()};
    }

    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static BigInteger wholeNanos(double value) {
        return new java.math.BigDecimal(value).toBigInteger();
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
