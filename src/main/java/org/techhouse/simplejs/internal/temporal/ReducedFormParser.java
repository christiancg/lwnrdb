package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.AnnotationParser.parseAnnotations;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.isDigit;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.isSign;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.parseDateTimeCore;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.readDigits;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.rejectUtcDesignator;
import static org.techhouse.simplejs.internal.temporal.TemporalParser.requireEnd;

import java.util.regex.Pattern;
import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class ReducedFormParser {
    public record ParsedYearMonth(Iso8601Fields date, String calendar) {
    }

    public record ParsedMonthDay(Iso8601Fields date, String calendar) {
    }

    public static ParsedYearMonth parseYearMonth(String input) {
        final var cursor = new TemporalCursor(input);
        final var reduced = tryParseReducedYearMonth(cursor);
        if (reduced != null) {
            final var annotations = parseAnnotations(cursor);
            requireEnd(cursor);
            return new ParsedYearMonth(reduced, annotations.calendar());
        }
        cursor.pos = 0;
        final var full = parseDateTimeCore(cursor, false);
        requireEnd(cursor);
        rejectUtcDesignator(full, input);
        return new ParsedYearMonth(full.date(), full.calendar());
    }

    public static ParsedMonthDay parseMonthDay(String input) {
        final var cursor = new TemporalCursor(input);
        final var reduced = tryParseReducedMonthDay(cursor);
        if (reduced != null) {
            final var annotations = parseAnnotations(cursor);
            requireEnd(cursor);
            return new ParsedMonthDay(reduced, annotations.calendar());
        }
        cursor.pos = 0;
        final var full = parseDateTimeCore(cursor, false);
        requireEnd(cursor);
        rejectUtcDesignator(full, input);
        return new ParsedMonthDay(full.date(), full.calendar());
    }

    static Iso8601Fields tryParseReducedYearMonth(TemporalCursor cursor) {
        final var savedPos = cursor.pos;
        try {
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
            if (!cursor.atEnd() && cursor.peek() == '-') {
                cursor.advance();
            }
            final var month = readDigits(cursor, 2);
            if (!cursor.atEnd() && (cursor.peek() == '-' || isDigit(cursor.peek()))) {
                cursor.pos = savedPos;
                return null;
            }
            return IsoCalendar.regulateCalendarDate(year, month, 1, RegulateOverflow.REJECT);
        } catch (RangeErrorException e) {
            cursor.pos = savedPos;
            return null;
        }
    }

    static Iso8601Fields tryParseReducedMonthDay(TemporalCursor cursor) {
        final var savedPos = cursor.pos;
        try {
            if (!cursor.atEnd() && cursor.peek() == '-') {
                if (cursor.pos + 1 < cursor.source.length() && cursor.source.charAt(cursor.pos + 1) == '-') {
                    cursor.advance();
                    cursor.advance();
                } else {
                    return null;
                }
            }
            final var month = readDigits(cursor, 2);
            final var extended = !cursor.atEnd() && cursor.peek() == '-';
            if (extended) {
                cursor.advance();
            }
            final var day = readDigits(cursor, 2);
            if (!extended && !cursor.atEnd() && isDigit(cursor.peek())) {
                cursor.pos = savedPos;
                return null;
            }
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

    static final Pattern AMBIGUOUS_YEAR_MONTH = Pattern.compile("(\\d{4})-?(\\d{2})");

    static final Pattern AMBIGUOUS_MONTH_DAY = Pattern.compile("(\\d{2})-?(\\d{2})");

    static void rejectAmbiguousBareTimeString(String input) {
        final var bracketIndex = input.indexOf('[');
        final var core = bracketIndex < 0 ? input : input.substring(0, bracketIndex);
        if (isAmbiguousDateShape(core)) {
            throw new RangeErrorException("'" + input + "' is ambiguous and requires T prefix");
        }
    }

    static boolean isAmbiguousDateShape(String core) {
        final var yearMonth = AMBIGUOUS_YEAR_MONTH.matcher(core);
        if (yearMonth.matches()) {
            final var month = Integer.parseInt(yearMonth.group(2));
            return month >= 1 && month <= 12;
        }
        final var monthDay = AMBIGUOUS_MONTH_DAY.matcher(core);
        if (monthDay.matches()) {
            final var month = Integer.parseInt(monthDay.group(1));
            final var day = Integer.parseInt(monthDay.group(2));
            try {
                IsoCalendar.regulateDate(1972, month, day, RegulateOverflow.REJECT);
                return true;
            } catch (RangeErrorException e) {
                return false;
            }
        }
        return false;
    }

    private ReducedFormParser() {
    }
}
