package org.techhouse.simplejs.internal.temporal;

import org.techhouse.simplejs.exceptions.RangeErrorException;

/**
 * Hand-written recursive-descent scanner for the Temporal ISO 8601 grammar (RFC 9557 profile).
 * Deliberately narrower than the full grammar: only the extended (dash/colon separated) format is
 * accepted (no "basic" no-separator format), and a duration's fractional part is only recognized
 * on the seconds component (the only one the current spec grammar allows). Malformed input always
 * throws {@link RangeErrorException} — Temporal string parsing failures are RangeErrors, not
 * SyntaxErrors, per spec.
 */
public final class TemporalParser {
    public record ParsedDateTime(Iso8601Fields date, IsoTimeFields time, String offset, String timeZoneId,
            String calendar) {
    }

    private TemporalParser() {
    }

    public static ParsedDateTime parseDate(String input) {
        final var cursor = new Cursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        return result;
    }

    public static ParsedDateTime parseTime(String input) {
        final var cursor = new Cursor(input);
        final var time = parseTimeSpec(cursor);
        final var calendar = parseAnnotations(cursor).calendar;
        requireEnd(cursor);
        return new ParsedDateTime(null, time, null, null, calendar);
    }

    public static ParsedDateTime parseDateTime(String input) {
        final var cursor = new Cursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        if (result.time() == null) {
            throw new RangeErrorException("Temporal date-time string is missing a time part: " + input);
        }
        return result;
    }

    public static ParsedDateTime parseInstant(String input) {
        final var cursor = new Cursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        if (result.time() == null || result.offset() == null) {
            throw new RangeErrorException("Temporal instant string requires a time and a UTC offset: " + input);
        }
        return result;
    }

    public static ParsedDateTime parseZonedDateTime(String input) {
        final var cursor = new Cursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        if (result.time() == null || result.timeZoneId() == null) {
            throw new RangeErrorException(
                    "Temporal zoned date-time string requires a time and a time zone annotation: " + input);
        }
        return result;
    }

    public record ParsedYearMonth(Iso8601Fields date, String calendar) {
    }

    public record ParsedMonthDay(Iso8601Fields date, String calendar) {
    }

    // TemporalYearMonthString accepts either the reduced "YYYY-MM" form (referenceISODay defaults to
    // 1, per CreateTemporalYearMonth) or a full date/date-time string (referenceISODay is whatever day
    // it carries) - the reduced form is tried first and backtracks on failure rather than needing a
    // separate grammar production.
    public static ParsedYearMonth parseYearMonth(String input) {
        final var cursor = new Cursor(input);
        final var reduced = tryParseReducedYearMonth(cursor);
        if (reduced != null) {
            final var annotations = parseAnnotations(cursor);
            requireEnd(cursor);
            return new ParsedYearMonth(reduced, annotations.calendar);
        }
        cursor.pos = 0;
        final var full = parseDateTimeCore(cursor);
        requireEnd(cursor);
        return new ParsedYearMonth(full.date(), full.calendar());
    }

    // TemporalMonthDayString accepts either the reduced "MM-DD"/"--MM-DD" form (referenceISOYear
    // defaults to 1972, a leap year - so "02-29" parses - per CreateTemporalMonthDay) or a full
    // date/date-time string (referenceISOYear is whatever year it carries).
    public static ParsedMonthDay parseMonthDay(String input) {
        final var cursor = new Cursor(input);
        final var reduced = tryParseReducedMonthDay(cursor);
        if (reduced != null) {
            final var annotations = parseAnnotations(cursor);
            requireEnd(cursor);
            return new ParsedMonthDay(reduced, annotations.calendar);
        }
        cursor.pos = 0;
        final var full = parseDateTimeCore(cursor);
        requireEnd(cursor);
        return new ParsedMonthDay(full.date(), full.calendar());
    }

    private static Iso8601Fields tryParseReducedYearMonth(Cursor cursor) {
        final var savedPos = cursor.pos;
        try {
            var sign = 1;
            var expanded = false;
            if (!cursor.atEnd() && isSign(cursor.peek())) {
                sign = cursor.peek() == '+' ? 1 : -1;
                expanded = true;
                cursor.advance();
            }
            final var year = sign * readDigits(cursor, expanded ? 6 : 4);
            cursor.expect('-');
            final var month = readDigits(cursor, 2);
            if (!cursor.atEnd() && cursor.peek() == '-') {
                cursor.pos = savedPos;
                return null;
            }
            return IsoCalendar.regulateDate(year, month, 1, RegulateOverflow.REJECT);
        } catch (RangeErrorException e) {
            cursor.pos = savedPos;
            return null;
        }
    }

    private static Iso8601Fields tryParseReducedMonthDay(Cursor cursor) {
        final var savedPos = cursor.pos;
        try {
            if (!cursor.atEnd() && cursor.peek() == '-') {
                if (cursor.pos + 1 < cursor.source.length() && cursor.source.charAt(cursor.pos + 1) == '-') {
                    cursor.advance();
                    cursor.advance();
                } else {
                    cursor.pos = savedPos;
                    return null;
                }
            }
            final var month = readDigits(cursor, 2);
            cursor.expect('-');
            final var day = readDigits(cursor, 2);
            if (!cursor.atEnd() && cursor.peek() == '-') {
                cursor.pos = savedPos;
                return null;
            }
            return IsoCalendar.regulateDate(1972, month, day, RegulateOverflow.REJECT);
        } catch (RangeErrorException e) {
            cursor.pos = savedPos;
            return null;
        }
    }

    public static String parseTimeZoneIdentifier(String input) {
        final var cursor = new Cursor(input);
        if (!cursor.atEnd() && isSign(cursor.peek())) {
            final var offset = parseOffset(cursor);
            requireEnd(cursor);
            return offset;
        }
        final var start = cursor.pos;
        while (!cursor.atEnd() && isTimeZoneNameChar(cursor.peek())) {
            cursor.advance();
        }
        if (cursor.pos == start) {
            throw new RangeErrorException("Invalid time zone identifier: " + input);
        }
        requireEnd(cursor);
        return input;
    }

    public static DurationFields parseDuration(String input) {
        final var cursor = new Cursor(input);
        var signFactor = 1;
        if (!cursor.atEnd() && isSign(cursor.peek())) {
            signFactor = cursor.peek() == '+' ? 1 : -1;
            cursor.advance();
        }
        cursor.expect('P');

        final var years = tryReadComponent(cursor, 'Y');
        final var months = tryReadComponent(cursor, 'M');
        final var weeks = tryReadComponent(cursor, 'W');
        final var days = tryReadComponent(cursor, 'D');
        var anyComponent = years != null || months != null || weeks != null || days != null;

        var hours = 0.0;
        var minutes = 0.0;
        var seconds = 0.0;
        var milliseconds = 0.0;
        var microseconds = 0.0;
        var nanoseconds = 0.0;
        if (!cursor.atEnd() && cursor.peek() == 'T') {
            cursor.advance();
            final var hoursValue = tryReadComponent(cursor, 'H');
            final var minutesValue = tryReadComponent(cursor, 'M');
            final var secondsValue = tryReadSecondsComponent(cursor);
            if (hoursValue == null && minutesValue == null && secondsValue == null) {
                throw new RangeErrorException("Duration time designator 'T' requires at least one component: " + input);
            }
            anyComponent = true;
            hours = orZero(hoursValue);
            minutes = orZero(minutesValue);
            if (secondsValue != null) {
                seconds = secondsValue.whole();
                milliseconds = secondsValue.milliseconds();
                microseconds = secondsValue.microseconds();
                nanoseconds = secondsValue.nanoseconds();
            }
        }
        requireEnd(cursor);
        if (!anyComponent) {
            throw new RangeErrorException("Empty Temporal duration string: " + input);
        }
        return new DurationFields(signFactor * orZero(years), signFactor * orZero(months), signFactor * orZero(weeks),
                signFactor * orZero(days), signFactor * hours, signFactor * minutes, signFactor * seconds,
                signFactor * milliseconds, signFactor * microseconds, signFactor * nanoseconds);
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private static ParsedDateTime parseDateTimeCore(Cursor cursor) {
        final var date = parseDateSpec(cursor);
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
        return new ParsedDateTime(date, time, offset, annotations.timeZoneId, annotations.calendar);
    }

    private static Iso8601Fields parseDateSpec(Cursor cursor) {
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
        cursor.expect('-');
        final var month = readDigits(cursor, 2);
        cursor.expect('-');
        final var day = readDigits(cursor, 2);
        return IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT);
    }

    private static IsoTimeFields parseTimeSpec(Cursor cursor) {
        final var hour = readDigits(cursor, 2);
        var minute = 0;
        var second = 0;
        var millisecond = 0;
        var microsecond = 0;
        var nanosecond = 0;
        if (!cursor.atEnd() && cursor.peek() == ':') {
            cursor.advance();
            minute = readDigits(cursor, 2);
            if (!cursor.atEnd() && cursor.peek() == ':') {
                cursor.advance();
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
        if (second > 59) {
            throw new RangeErrorException("second must be in the range 0..59, got " + second + " (leap seconds are"
                    + " not representable in Temporal)");
        }
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static String parseOffset(Cursor cursor) {
        final var start = cursor.pos;
        cursor.advance();
        readDigits(cursor, 2);
        cursor.expect(':');
        readDigits(cursor, 2);
        if (!cursor.atEnd() && cursor.peek() == ':') {
            cursor.advance();
            readDigits(cursor, 2);
            if (!cursor.atEnd() && cursor.peek() == '.') {
                cursor.advance();
                readFractionDigits(cursor);
            }
        }
        return cursor.source.substring(start, cursor.pos);
    }

    private record Annotations(String timeZoneId, String calendar) {
    }

    private static Annotations parseAnnotations(Cursor cursor) {
        String timeZoneId = null;
        String calendar = null;
        var index = 0;
        while (!cursor.atEnd() && cursor.peek() == '[') {
            cursor.advance();
            var critical = false;
            if (!cursor.atEnd() && cursor.peek() == '!') {
                critical = true;
                cursor.advance();
            }
            final var contentStart = cursor.pos;
            while (true) {
                if (cursor.atEnd()) {
                    throw new RangeErrorException("Unterminated Temporal annotation: " + cursor.source);
                }
                if (cursor.peek() == ']') {
                    break;
                }
                cursor.advance();
            }
            final var content = cursor.source.substring(contentStart, cursor.pos);
            cursor.advance();
            final var equalsIndex = content.indexOf('=');
            if (equalsIndex < 0) {
                if (index != 0) {
                    throw new RangeErrorException(
                            "A bare time zone annotation must be the first annotation: " + cursor.source);
                }
                timeZoneId = content;
            } else {
                final var key = content.substring(0, equalsIndex);
                final var value = content.substring(equalsIndex + 1);
                if ("u-ca".equals(key)) {
                    if (calendar == null) {
                        calendar = value;
                    }
                } else if (critical) {
                    throw new RangeErrorException("Unknown critical Temporal annotation: " + key);
                }
            }
            index++;
        }
        return new Annotations(timeZoneId, calendar);
    }

    private static Double tryReadComponent(Cursor cursor, char designator) {
        if (cursor.atEnd() || !isDigit(cursor.peek())) {
            return null;
        }
        final var savedPos = cursor.pos;
        final var digitsStart = cursor.pos;
        while (!cursor.atEnd() && isDigit(cursor.peek())) {
            cursor.advance();
        }
        if (!cursor.atEnd() && cursor.peek() == designator) {
            final var digits = cursor.source.substring(digitsStart, cursor.pos);
            cursor.advance();
            return Double.parseDouble(digits);
        }
        cursor.pos = savedPos;
        return null;
    }

    private record SecondsComponent(double whole, int milliseconds, int microseconds, int nanoseconds) {
    }

    private static SecondsComponent tryReadSecondsComponent(Cursor cursor) {
        if (cursor.atEnd() || !isDigit(cursor.peek())) {
            return null;
        }
        final var savedPos = cursor.pos;
        final var digitsStart = cursor.pos;
        while (!cursor.atEnd() && isDigit(cursor.peek())) {
            cursor.advance();
        }
        final var digitsEnd = cursor.pos;
        var fraction = "000000000";
        if (!cursor.atEnd() && (cursor.peek() == '.' || cursor.peek() == ',')) {
            cursor.advance();
            fraction = readFractionDigits(cursor);
        }
        if (!cursor.atEnd() && cursor.peek() == 'S') {
            final var whole = Double.parseDouble(cursor.source.substring(digitsStart, digitsEnd));
            cursor.advance();
            return new SecondsComponent(whole, Integer.parseInt(fraction.substring(0, 3)),
                    Integer.parseInt(fraction.substring(3, 6)), Integer.parseInt(fraction.substring(6, 9)));
        }
        cursor.pos = savedPos;
        return null;
    }

    private static String readFractionDigits(Cursor cursor) {
        final var start = cursor.pos;
        while (!cursor.atEnd() && isDigit(cursor.peek())) {
            cursor.advance();
        }
        if (cursor.pos == start) {
            throw new RangeErrorException("Expected fractional digits: " + cursor.source);
        }
        var digits = cursor.source.substring(start, cursor.pos);
        if (digits.length() > 9) {
            digits = digits.substring(0, 9);
        } else {
            digits = digits + "0".repeat(9 - digits.length());
        }
        return digits;
    }

    private static int readDigits(Cursor cursor, int count) {
        final var start = cursor.pos;
        for (var i = 0; i < count; i++) {
            if (cursor.atEnd() || !isDigit(cursor.peek())) {
                throw new RangeErrorException("Invalid Temporal string: " + cursor.source);
            }
            cursor.advance();
        }
        return Integer.parseInt(cursor.source.substring(start, cursor.pos));
    }

    private static void requireEnd(Cursor cursor) {
        if (!cursor.atEnd()) {
            throw new RangeErrorException("Trailing characters in Temporal string: " + cursor.source);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSign(char c) {
        return c == '+' || c == '-' || c == '−';
    }

    private static boolean isTimeZoneNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '_' || c == '-' || c == '+' || c == '.';
    }

    private static final class Cursor {
        private final String source;
        private int pos;

        private Cursor(String source) {
            this.source = source;
        }

        private boolean atEnd() {
            return pos >= source.length();
        }

        private char peek() {
            return source.charAt(pos);
        }

        private void advance() {
            pos++;
        }

        private void expect(char expected) {
            if (atEnd() || peek() != expected) {
                throw new RangeErrorException("Invalid Temporal string: " + source);
            }
            advance();
        }
    }
}
