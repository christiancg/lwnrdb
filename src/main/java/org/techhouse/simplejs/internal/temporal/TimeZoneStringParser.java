package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.TemporalParser.ParsedDateTime;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.isDigit;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.isSign;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.parseDateTimeCore;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.readDigits;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.readFractionDigits;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.requireEnd;

import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class TimeZoneStringParser {
    public static String parseTimeZoneIdentifier(String input) {
        final var cursor = new TemporalCursor(input);
        if (!cursor.atEnd() && isSign(cursor.peek())) {
            final var offset = parseTimeZoneOffset(cursor);
            requireEnd(cursor);
            return offset;
        }
        final var start = cursor.pos;
        if (!cursor.atEnd() && Character.isLetter(cursor.peek())) {
            while (!cursor.atEnd() && isTimeZoneNameChar(cursor.peek())) {
                cursor.advance();
            }
        }
        if (cursor.pos == start) {
            throw new RangeErrorException("Invalid time zone identifier: " + input);
        }
        requireEnd(cursor);
        if (TemporalCalendarIdentifier.asciiEqualsIgnoreCase(input, "utc")) {
            return "UTC";
        }
        return input;
    }

    static String parseTimeZoneOffset(TemporalCursor cursor) {
        final var sign = cursor.peek();
        cursor.advance();
        final var hourStart = cursor.pos;
        readDigits(cursor, 2);
        final var hour = cursor.source.substring(hourStart, cursor.pos);
        var minute = "00";
        if (!cursor.atEnd() && cursor.peek() == ':') {
            cursor.advance();
            final var minuteStart = cursor.pos;
            readDigits(cursor, 2);
            minute = cursor.source.substring(minuteStart, cursor.pos);
        } else if (!cursor.atEnd() && isDigit(cursor.peek())) {
            final var minuteStart = cursor.pos;
            readDigits(cursor, 2);
            minute = cursor.source.substring(minuteStart, cursor.pos);
        }
        return sign + hour + ":" + minute;
    }

    public static String parseTimeZoneIdentifierFlexible(String input) {
        try {
            return parseTimeZoneIdentifier(input);
        } catch (RangeErrorException primary) {
            final ParsedDateTime result;
            try {
                final var cursor = new TemporalCursor(input);
                result = parseDateTimeCore(cursor);
                requireEnd(cursor);
            } catch (RangeErrorException ignored) {
                throw primary;
            }
            if (result.timeZoneId() != null) {
                return parseTimeZoneIdentifier(result.timeZoneId());
            }
            if ("Z".equals(result.offset())) {
                return "UTC";
            }
            if (result.offset() != null) {
                return parseTimeZoneIdentifier(result.offset());
            }
            throw primary;
        }
    }

    public static void requireValidUtcOffset(String input) {
        final var cursor = new TemporalCursor(input);
        if (cursor.atEnd() || !isSign(cursor.peek())) {
            throw new RangeErrorException("Invalid UTC offset: " + input);
        }
        parseOffset(cursor);
        requireEnd(cursor);
    }

    static String parseOffset(TemporalCursor cursor) {
        final var start = cursor.pos;
        cursor.advance();
        final var hour = readDigits(cursor, 2);
        var minute = 0;
        var second = 0;
        final var hasMinute = !cursor.atEnd() && (cursor.peek() == ':' || isDigit(cursor.peek()));
        if (hasMinute) {
            final var extended = cursor.peek() == ':';
            if (extended) {
                cursor.advance();
            }
            minute = readDigits(cursor, 2);
            final var hasSeconds = !cursor.atEnd() && (extended ? cursor.peek() == ':' : isDigit(cursor.peek()));
            if (hasSeconds) {
                if (extended) {
                    cursor.advance();
                }
                second = readDigits(cursor, 2);
                if (!cursor.atEnd() && (cursor.peek() == '.' || cursor.peek() == ',')) {
                    cursor.advance();
                    readFractionDigits(cursor);
                }
            }
        }
        if (hour > 23) {
            throw new RangeErrorException("UTC offset hour must be in the range 0..23, got " + hour);
        }
        if (minute > 59) {
            throw new RangeErrorException("UTC offset minute must be in the range 0..59, got " + minute);
        }
        if (second > 59) {
            throw new RangeErrorException("UTC offset second must be in the range 0..59, got " + second);
        }
        return cursor.source.substring(start, cursor.pos);
    }

    static boolean isTimeZoneNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '_' || c == '-' || c == '+' || c == '.';
    }

    private TimeZoneStringParser() {
    }
}
