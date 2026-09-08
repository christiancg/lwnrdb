package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.AnnotationParser.parseAnnotations;
import static org.techhouse.simplejs.internal.temporal.ReducedFormParser.rejectAmbiguousBareTimeString;
import static org.techhouse.simplejs.internal.temporal.TimeZoneStringParser.parseOffset;

import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class TemporalParser {
    public record ParsedDateTime(Iso8601Fields date, IsoTimeFields time, String offset, String timeZoneId,
            String calendar) {
    }

    private TemporalParser() {
    }

    public static ParsedDateTime parseDate(String input) {
        final var cursor = new TemporalCursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        rejectUtcDesignator(result, input);
        return result;
    }

    public static ParsedDateTime parseRelativeToString(String input) {
        final var cursor = new TemporalCursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        return result;
    }

    static void rejectUtcDesignator(ParsedDateTime result, String input) {
        if ("Z".equals(result.offset())) {
            throw new RangeErrorException("A UTC designator is not valid in this Temporal string: " + input);
        }
    }

    public static ParsedDateTime parseTime(String input) {
        try {
            final var cursor = new TemporalCursor(input);
            final var hasTDesignator = !cursor.atEnd() && (cursor.peek() == 'T' || cursor.peek() == 't');
            if (!hasTDesignator) {
                rejectAmbiguousBareTimeString(input);
            }
            if (hasTDesignator) {
                cursor.advance();
            }
            final var time = parseTimeSpec(cursor);
            String offset = null;
            if (!cursor.atEnd() && (cursor.peek() == 'Z' || cursor.peek() == 'z')) {
                cursor.advance();
                offset = "Z";
            } else if (!cursor.atEnd() && isSign(cursor.peek())) {
                offset = parseOffset(cursor);
            }
            final var calendar = parseAnnotations(cursor).calendar();
            requireEnd(cursor);
            if ("Z".equals(offset)) {
                throw new RangeErrorException("A UTC designator is not valid in this Temporal string: " + input);
            }
            return new ParsedDateTime(null, time, null, null, calendar);
        } catch (RangeErrorException primary) {
            final ParsedDateTime result;
            try {
                final var cursor = new TemporalCursor(input);
                result = parseDateTimeCore(cursor);
                requireEnd(cursor);
            } catch (RangeErrorException ignored) {
                throw primary;
            }
            if (result.time() == null || "Z".equals(result.offset())) {
                throw primary;
            }
            return new ParsedDateTime(null, result.time(), null, null, result.calendar());
        }
    }

    public static ParsedDateTime parseDateTime(String input) {
        final var cursor = new TemporalCursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        if (result.time() == null) {
            throw new RangeErrorException("Temporal date-time string is missing a time part: " + input);
        }
        rejectUtcDesignator(result, input);
        return result;
    }

    public static ParsedDateTime parseInstant(String input) {
        final var cursor = new TemporalCursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        if (result.time() == null || result.offset() == null) {
            throw new RangeErrorException("Temporal instant string requires a time and a UTC offset: " + input);
        }
        return result;
    }

    public static ParsedDateTime parseZonedDateTime(String input) {
        final var cursor = new TemporalCursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        if (result.timeZoneId() == null) {
            throw new RangeErrorException("Temporal zoned date-time string requires a time zone annotation: " + input);
        }
        return result;
    }

    static ParsedDateTime parseDateTimeCore(TemporalCursor cursor) {
        return parseDateTimeCore(cursor, true);
    }

    static ParsedDateTime parseDateTimeCore(TemporalCursor cursor, boolean enforceRange) {
        final var date = parseDateSpec(cursor, enforceRange);
        IsoTimeFields time = null;
        String offset = null;
        if (!cursor.atEnd() && (cursor.peek() == 'T' || cursor.peek() == 't' || cursor.peek() == ' ')) {
            cursor.advance();
            time = parseTimeSpec(cursor);
            if (!cursor.atEnd() && (cursor.peek() == 'Z' || cursor.peek() == 'z')) {
                cursor.advance();
                offset = "Z";
            } else if (!cursor.atEnd() && isSign(cursor.peek())) {
                offset = parseOffset(cursor);
            }
        }
        final var annotations = parseAnnotations(cursor);
        return new ParsedDateTime(date, time, offset, annotations.timeZoneId(), annotations.calendar());
    }

    private static Iso8601Fields parseDateSpec(TemporalCursor cursor, boolean enforceRange) {
        var sign = 1;
        var expanded = false;
        if (!cursor.atEnd() && isSign(cursor.peek())) {
            sign = cursor.peek() == '+' ? 1 : -1;
            expanded = true;
            cursor.advance();
        }
        final var yearDigits = readDigits(cursor, expanded ? 6 : 4);
        if (expanded && sign < 0 && yearDigits == 0) {
            throw new RangeErrorException("Invalid Temporal date: expanded year -000000 is not allowed");
        }
        final var year = sign * yearDigits;
        final var extended = !cursor.atEnd() && cursor.peek() == '-';
        if (extended) {
            cursor.advance();
        }
        final var month = readDigits(cursor, 2);
        if (extended) {
            cursor.expectDash();
        }
        final var day = readDigits(cursor, 2);
        return enforceRange
                ? IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT)
                : IsoCalendar.regulateCalendarDate(year, month, day, RegulateOverflow.REJECT);
    }

    private static IsoTimeFields parseTimeSpec(TemporalCursor cursor) {
        final var hour = readDigits(cursor, 2);
        var minute = 0;
        var second = 0;
        var millisecond = 0;
        var microsecond = 0;
        var nanosecond = 0;
        final var hasMinute = !cursor.atEnd() && (cursor.peek() == ':' || isDigit(cursor.peek()));
        if (hasMinute) {
            final var extended = cursor.peek() == ':';
            if (extended) {
                cursor.advance();
            }
            minute = readDigits(cursor, 2);
            final var hasSecond = !cursor.atEnd() && (extended ? cursor.peek() == ':' : isDigit(cursor.peek()));
            if (hasSecond) {
                if (extended) {
                    cursor.advance();
                }
                second = readDigits(cursor, 2);
                if (!cursor.atEnd() && (cursor.peek() == '.' || cursor.peek() == ',')) {
                    cursor.advance();
                    final var fraction = readFractionDigits(cursor);
                    millisecond = Integer.parseInt(fraction.substring(0, 3));
                    microsecond = Integer.parseInt(fraction.substring(3, 6));
                    nanosecond = Integer.parseInt(fraction.substring(6, 9));
                }
            }
        }
        if (hour > 23) {
            throw new RangeErrorException("hour must be in the range 0..23, got " + hour);
        }
        if (minute > 59) {
            throw new RangeErrorException("minute must be in the range 0..59, got " + minute);
        }
        if (second == 60) {
            second = 59;
        } else if (second > 60) {
            throw new RangeErrorException("second must be in the range 0..59, got " + second);
        }
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    static String readFractionDigits(TemporalCursor cursor) {
        final var start = cursor.pos;
        while (!cursor.atEnd() && isDigit(cursor.peek())) {
            cursor.advance();
        }
        final var digits = cursor.source.substring(start, cursor.pos);
        if (digits.isEmpty()) {
            throw new RangeErrorException("Expected fractional digits: " + cursor.source);
        }
        if (digits.length() > 9) {
            throw new RangeErrorException("no more than 9 decimal places are allowed: " + cursor.source);
        }
        return digits + "0".repeat(9 - digits.length());
    }

    static int readDigits(TemporalCursor cursor, int count) {
        final var start = cursor.pos;
        for (var i = 0; i < count; i++) {
            if (cursor.atEnd() || !isDigit(cursor.peek())) {
                throw new RangeErrorException("Invalid Temporal string: " + cursor.source);
            }
            cursor.advance();
        }
        return Integer.parseInt(cursor.source.substring(start, cursor.pos));
    }

    static void requireEnd(TemporalCursor cursor) {
        if (!cursor.atEnd()) {
            throw new RangeErrorException("Trailing characters in Temporal string: " + cursor.source);
        }
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean isSign(char c) {
        return c == '+' || c == '-';
    }
}
