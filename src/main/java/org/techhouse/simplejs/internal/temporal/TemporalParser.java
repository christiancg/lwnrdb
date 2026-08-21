package org.techhouse.simplejs.internal.temporal;

import java.util.regex.Pattern;
import org.techhouse.simplejs.exceptions.RangeErrorException;

/**
 * Hand-written recursive-descent scanner for the Temporal ISO 8601 grammar (RFC 9557 profile).
 * Both the extended (dash/colon separated) and basic (no-separator) forms are accepted for the
 * date, time and UTC-offset components independently (each chooses extended vs basic based on
 * whether a separator follows its first field), matching the mixed-format inputs test262 exercises
 * (e.g. {@code "19761118T15:23:30.1-0800"}). Malformed input always throws
 * {@link RangeErrorException} — Temporal string parsing failures are RangeErrors, not
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
        rejectUtcDesignator(result, input);
        return result;
    }

    // Temporal.Duration's relativeTo string is unique among the Plain-type coercions: a bare 'Z' is
    // only invalid when there is no bracketed time zone annotation to give it meaning (a Z-with-
    // bracket names a real exact time in that zone), so it can't reuse parseDate/parseDateTime's
    // unconditional rejection - the caller decides based on whether a bracket is present.
    public static ParsedDateTime parseRelativeToString(String input) {
        final var cursor = new Cursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        return result;
    }

    // A bare 'Z' UTC designator forces Instant/ZonedDateTime interpretation and is specifically
    // excluded from every Plain-type string coercion (PlainDate/PlainDateTime/PlainYearMonth/
    // PlainMonthDay/PlainTime); a numeric offset like "+00:00" is fine and simply ignored.
    private static void rejectUtcDesignator(ParsedDateTime result, String input) {
        if ("Z".equals(result.offset())) {
            throw new RangeErrorException("A UTC designator is not valid in this Temporal string: " + input);
        }
    }

    // TemporalTimeString ::: AnnotatedTime | AnnotatedDateTimeTimeRequired - a bare (optionally
    // 'T'-prefixed) time with annotations, or a full date-time string (whose date part is then
    // discarded, keeping only the time and any calendar annotation). Tried in that order since a
    // valid AnnotatedTime never has the shape of a date (a date's fixed-width year makes the two
    // productions unambiguous in practice).
    public static ParsedDateTime parseTime(String input) {
        try {
            final var cursor = new Cursor(input);
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
            final var calendar = parseAnnotations(cursor).calendar;
            requireEnd(cursor);
            if ("Z".equals(offset)) {
                throw new RangeErrorException("A UTC designator is not valid in this Temporal string: " + input);
            }
            return new ParsedDateTime(null, time, null, null, calendar);
        } catch (RangeErrorException primary) {
            final ParsedDateTime result;
            try {
                final var cursor = new Cursor(input);
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
        final var cursor = new Cursor(input);
        final var result = parseDateTimeCore(cursor);
        requireEnd(cursor);
        if (result.time() == null) {
            throw new RangeErrorException("Temporal date-time string is missing a time part: " + input);
        }
        rejectUtcDesignator(result, input);
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
        // Unlike TemporalDateTimeString (PlainDateTime), a ZonedDateTimeString's time part is
        // optional (defaulting to midnight) as long as the mandatory time zone annotation is present.
        if (result.timeZoneId() == null) {
            throw new RangeErrorException("Temporal zoned date-time string requires a time zone annotation: " + input);
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
        final var full = parseDateTimeCore(cursor, false);
        requireEnd(cursor);
        rejectUtcDesignator(full, input);
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
        final var full = parseDateTimeCore(cursor, false);
        requireEnd(cursor);
        rejectUtcDesignator(full, input);
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
            final var yearDigits = readDigits(cursor, expanded ? 6 : 4);
            if (expanded && sign < 0 && yearDigits == 0) {
                throw new RangeErrorException("Invalid Temporal date: expanded year -000000 is not allowed");
            }
            final var year = sign * yearDigits;
            // The year-month separator, like every other Temporal string separator, chooses extended
            // vs basic independently (e.g. "197611" is accepted alongside "1976-11").
            if (!cursor.atEnd() && cursor.peek() == '-') {
                cursor.advance();
            }
            final var month = readDigits(cursor, 2);
            // A day component (extended "-DD" or basic "DD") immediately following means this is
            // actually a full date/date-time, not a reduced year-month - back off and let the caller's
            // full-date fallback parse it (with its own real day, rather than defaulting to day 1).
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
            // TemporalMonthDayString allows both the extended ("--10-01") and basic ("--1001",
            // "1001") reduced forms - a separator is optional, exactly like the year-month reduced
            // form's own extended/basic choice.
            final var extended = !cursor.atEnd() && cursor.peek() == '-';
            if (extended) {
                cursor.advance();
            }
            final var day = readDigits(cursor, 2);
            // A basic-form month+day immediately followed by more digits is really the start of a
            // full "YYYYMMDD" basic date (e.g. a year whose first four digits happen to look like a
            // valid month+day) - back off and let the full date-time parser consume it instead of
            // silently truncating it.
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

    // TimeZoneIdentifier ::: TimeZoneNumericUTCOffset | TimeZoneIANAName - a bare, minute-precision
    // numeric offset or an IANA name. Used wherever spec requires the argument to literally be a
    // TimeZoneIdentifier (e.g. the ZonedDateTime constructor's timeZone parameter, or a bracket
    // annotation's already-extracted content) rather than the broader TimeZoneString convenience.
    public static String parseTimeZoneIdentifier(String input) {
        final var cursor = new Cursor(input);
        if (!cursor.atEnd() && isSign(cursor.peek())) {
            final var offset = parseTimeZoneOffset(cursor);
            requireEnd(cursor);
            return offset;
        }
        final var start = cursor.pos;
        // A TimeZoneIANAName always begins with a letter (tzdata has no digit-leading zones); this
        // keeps a bare date-time-shaped string (e.g. "2021-08-19T1730Z") from being misread as a
        // literal (bogus) IANA name instead of falling back to the date-time/annotation grammar.
        if (!cursor.atEnd() && Character.isLetter(cursor.peek())) {
            while (!cursor.atEnd() && isTimeZoneNameChar(cursor.peek())) {
                cursor.advance();
            }
        }
        if (cursor.pos == start) {
            throw new RangeErrorException("Invalid time zone identifier: " + input);
        }
        requireEnd(cursor);
        // "UTC" is the one time zone identifier the spec requires callers to recognize
        // case-insensitively and report back in its canonical case; other IANA names are returned
        // verbatim (this engine defers to java.time for their canonical form, if any).
        if (TemporalCalendarIdentifier.asciiEqualsIgnoreCase(input, "utc")) {
            return "UTC";
        }
        return input;
    }

    // A bare TimeZoneIdentifier's numeric offset is hour precision at minimum and minute precision at
    // most (no seconds/fraction) - distinct from the general UTC-offset grammar used inside a full
    // date-time string, where sub-minute precision is accepted but purely informational. The minute
    // part itself is optional (e.g. "+00", not just "+00:00"/"+0000").
    private static String parseTimeZoneOffset(Cursor cursor) {
        final var start = cursor.pos;
        cursor.advance();
        readDigits(cursor, 2);
        if (!cursor.atEnd() && cursor.peek() == ':') {
            cursor.advance();
            readDigits(cursor, 2);
        } else if (!cursor.atEnd() && isDigit(cursor.peek())) {
            readDigits(cursor, 2);
        }
        return cursor.source.substring(start, cursor.pos);
    }

    // TimeZoneString ::: TimeZoneIdentifier | Date TimeZoneNameRequired? Annotations - the broader
    // convenience form accepted by `.from()`, bag `timeZone` fields and the `Now.*ISO(timeZone)`
    // family: either a bare TimeZoneIdentifier, or a full date-time string whose bracket time zone
    // annotation (or, absent one, its UTC offset / 'Z' designator) supplies the identifier.
    public static String parseTimeZoneIdentifierFlexible(String input) {
        try {
            return parseTimeZoneIdentifier(input);
        } catch (RangeErrorException primary) {
            final ParsedDateTime result;
            try {
                final var cursor = new Cursor(input);
                result = parseDateTimeCore(cursor);
                requireEnd(cursor);
            } catch (RangeErrorException ignored) {
                throw primary;
            }
            if (result.timeZoneId() != null) {
                return parseTimeZoneIdentifier(result.timeZoneId());
            }
            // A bare 'Z' with no bracket annotation names the UTC time zone itself, not merely a
            // zero numeric offset.
            if ("Z".equals(result.offset())) {
                return "UTC";
            }
            if (result.offset() != null) {
                return parseTimeZoneIdentifier(result.offset());
            }
            throw primary;
        }
    }

    public static DurationFields parseDuration(String input) {
        final var cursor = new Cursor(input);
        var signFactor = 1;
        if (!cursor.atEnd() && isSign(cursor.peek())) {
            signFactor = cursor.peek() == '+' ? 1 : -1;
            cursor.advance();
        }
        if (cursor.atEnd() || !isDesignator(cursor.peek(), 'P')) {
            throw new RangeErrorException("Invalid Temporal duration string: " + input);
        }
        cursor.advance();

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
        if (!cursor.atEnd() && isDesignator(cursor.peek(), 'T')) {
            cursor.advance();
            // Per the DurationTime grammar, only the last time component present may carry a
            // fractional part (DurationHoursPart's fraction alternative excludes a following minutes
            // or seconds part, and likewise for DurationMinutesPart) - so once a fraction is read on
            // hours or minutes, the remaining components are not attempted at all.
            final var hoursComponent = tryReadTimeComponent(cursor, 'H');
            TimeComponent minutesComponent = null;
            TimeComponent secondsComponent = null;
            if (hoursComponent == null || !hoursComponent.hasFraction()) {
                minutesComponent = tryReadTimeComponent(cursor, 'M');
                if (minutesComponent == null || !minutesComponent.hasFraction()) {
                    secondsComponent = tryReadTimeComponent(cursor, 'S');
                }
            }
            if (hoursComponent == null && minutesComponent == null && secondsComponent == null) {
                throw new RangeErrorException("Duration time designator 'T' requires at least one component: " + input);
            }
            anyComponent = true;
            hours = hoursComponent == null ? 0 : hoursComponent.whole();
            minutes = minutesComponent == null ? 0 : minutesComponent.whole();
            seconds = secondsComponent == null ? 0 : secondsComponent.whole();
            if (hoursComponent != null && hoursComponent.hasFraction()) {
                final var totalNanos = hoursComponent.fractionNanos() * 3600L;
                final var wholeSeconds = totalNanos / 1_000_000_000L;
                final var remainder = totalNanos % 1_000_000_000L;
                final long extraMinutes = wholeSeconds / 60L;
                final long extraSeconds = wholeSeconds % 60L;
                final long ms = remainder / 1_000_000L;
                final long us = (remainder / 1_000L) % 1000L;
                final long ns = remainder % 1000L;
                minutes += extraMinutes;
                seconds += extraSeconds;
                milliseconds = ms;
                microseconds = us;
                nanoseconds = ns;
            } else if (minutesComponent != null && minutesComponent.hasFraction()) {
                final var totalNanos = minutesComponent.fractionNanos() * 60L;
                final long extraSeconds = totalNanos / 1_000_000_000L;
                final var remainder = totalNanos % 1_000_000_000L;
                final long ms = remainder / 1_000_000L;
                final long us = (remainder / 1_000L) % 1000L;
                final long ns = remainder % 1000L;
                seconds += extraSeconds;
                milliseconds = ms;
                microseconds = us;
                nanoseconds = ns;
            } else if (secondsComponent != null && secondsComponent.hasFraction()) {
                final var totalNanos = secondsComponent.fractionNanos();
                final long ms = totalNanos / 1_000_000L;
                final long us = (totalNanos / 1_000L) % 1000L;
                final long ns = totalNanos % 1000L;
                milliseconds = ms;
                microseconds = us;
                nanoseconds = ns;
            }
        }
        requireEnd(cursor);
        if (!anyComponent) {
            throw new RangeErrorException("Empty Temporal duration string: " + input);
        }
        // An absent component is 0, and a negative overall sign must not turn that into a spurious
        // -0 (IEEE754 negative * positive zero = negative zero) - every field is normalised back to
        // +0 rather than only the ones this parser happens to track as a nullable Double.
        return new DurationFields(normalizeZero(signFactor * orZero(years)), normalizeZero(signFactor * orZero(months)),
                normalizeZero(signFactor * orZero(weeks)), normalizeZero(signFactor * orZero(days)),
                normalizeZero(signFactor * hours), normalizeZero(signFactor * minutes),
                normalizeZero(signFactor * seconds), normalizeZero(signFactor * milliseconds),
                normalizeZero(signFactor * microseconds), normalizeZero(signFactor * nanoseconds));
    }

    private static double normalizeZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    // A no-T-prefix TimeSpec is a syntax error when it could equally be read as a reduced-precision
    // Date (DateSpecYearMonth "YYYY[-]MM" or DateSpecMonthDay "MM[-]DD") - a bracket annotation never
    // resolves the ambiguity, so it is stripped before the shape check. Per spec this is a pure
    // grammar-shape test, not a "try both and see which succeeds" runtime check.
    private static final Pattern AMBIGUOUS_YEAR_MONTH = Pattern.compile("(\\d{4})-?(\\d{2})");
    private static final Pattern AMBIGUOUS_MONTH_DAY = Pattern.compile("(\\d{2})-?(\\d{2})");

    private static void rejectAmbiguousBareTimeString(String input) {
        final var bracketIndex = input.indexOf('[');
        final var core = bracketIndex < 0 ? input : input.substring(0, bracketIndex);
        if (isAmbiguousDateShape(core)) {
            throw new RangeErrorException("'" + input + "' is ambiguous and requires T prefix");
        }
    }

    private static boolean isAmbiguousDateShape(String core) {
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

    private static ParsedDateTime parseDateTimeCore(Cursor cursor) {
        return parseDateTimeCore(cursor, true);
    }

    private static ParsedDateTime parseDateTimeCore(Cursor cursor, boolean enforceRange) {
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
        return new ParsedDateTime(date, time, offset, annotations.timeZoneId, annotations.calendar);
    }

    private static Iso8601Fields parseDateSpec(Cursor cursor, boolean enforceRange) {
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
            cursor.expect('-');
        }
        final var day = readDigits(cursor, 2);
        return enforceRange
                ? IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT)
                : IsoCalendar.regulateCalendarDate(year, month, day, RegulateOverflow.REJECT);
    }

    private static IsoTimeFields parseTimeSpec(Cursor cursor) {
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
        // A leap second (":60") is a syntactically valid ISO string everywhere - per test262 it is
        // always clamped to :59, even under `overflow: "reject"` (which only governs the numeric
        // property-bag `second` field, not the string grammar).
        if (second == 60) {
            second = 59;
        } else if (second > 60) {
            throw new RangeErrorException("second must be in the range 0..59, got " + second);
        }
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static String parseOffset(Cursor cursor) {
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

    private record Annotations(String timeZoneId, String calendar) {
    }

    private static Annotations parseAnnotations(Cursor cursor) {
        String timeZoneId = null;
        String calendar = null;
        var calendarCount = 0;
        var calendarCritical = false;
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
                // A bracket time zone annotation's content must itself be a syntactically valid
                // TimeZoneIdentifier (RFC 9557) - a numeric-offset annotation is minute-precision only,
                // same as a bare TimeZoneIdentifier - checked here so every string production rejects a
                // malformed annotation, not just the callers that go on to resolve it into a zone.
                parseTimeZoneIdentifier(content);
                timeZoneId = content;
            } else {
                final var key = content.substring(0, equalsIndex);
                final var value = content.substring(equalsIndex + 1);
                if (!isValidAnnotationKey(key)) {
                    throw new RangeErrorException("Annotation keys must be lowercase: " + cursor.source);
                }
                if ("u-ca".equals(key)) {
                    calendarCount++;
                    // RFC 9557: it is a Syntax Error for a Temporal string to contain more than one
                    // calendar annotation if any of them is critical, even when their values agree.
                    if (calendarCount > 1 && (critical || calendarCritical)) {
                        throw new RangeErrorException(
                                "More than one calendar annotation with a critical flag: " + cursor.source);
                    }
                    if (critical) {
                        calendarCritical = true;
                    }
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

    // Annotation keys are restricted to lowercase ASCII letters, digits and hyphens (RFC 9557
    // a-key-leading-char / a-key-char); an uppercase key like "U-CA" is a syntax error, not merely an
    // unrecognized key.
    private static boolean isValidAnnotationKey(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (var i = 0; i < key.length(); i++) {
            final var c = key.charAt(i);
            final var valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!valid) {
                return false;
            }
        }
        return true;
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
        if (!cursor.atEnd() && isDesignator(cursor.peek(), designator)) {
            final var digits = cursor.source.substring(digitsStart, cursor.pos);
            cursor.advance();
            return Double.parseDouble(digits);
        }
        cursor.pos = savedPos;
        return null;
    }

    // Unlike the date components (Y/M/W/D, which the grammar never allows a fraction on), any of the
    // time components (H/M/S) may carry a fraction - `hasFraction`/`fractionNanos` (the 9-digit
    // fraction interpreted as an integer numerator, so `fractionNanos * unitSeconds` is exactly the
    // fractional remainder in nanoseconds with no floating-point rounding) let the caller decide how
    // to redistribute it into the lower units, since only the last-present component's fraction is
    // ever meaningful.
    private record TimeComponent(double whole, boolean hasFraction, long fractionNanos) {
    }

    private static TimeComponent tryReadTimeComponent(Cursor cursor, char designator) {
        if (cursor.atEnd() || !isDigit(cursor.peek())) {
            return null;
        }
        final var savedPos = cursor.pos;
        final var digitsStart = cursor.pos;
        while (!cursor.atEnd() && isDigit(cursor.peek())) {
            cursor.advance();
        }
        final var digitsEnd = cursor.pos;
        var hasFraction = false;
        var fraction = "000000000";
        if (!cursor.atEnd() && (cursor.peek() == '.' || cursor.peek() == ',')) {
            cursor.advance();
            fraction = readFractionDigits(cursor);
            hasFraction = true;
        }
        if (!cursor.atEnd() && isDesignator(cursor.peek(), designator)) {
            final var whole = Double.parseDouble(cursor.source.substring(digitsStart, digitsEnd));
            cursor.advance();
            return new TimeComponent(whole, hasFraction, Long.parseLong(fraction));
        }
        cursor.pos = savedPos;
        return null;
    }

    // The duration designators (P/Y/M/W/D/T/H/S) are matched ASCII-case-insensitively (a lowercase
    // duration string like "p1y1m1dt1h1m1s" is valid per test262), unlike every other Temporal string
    // literal (date/time separators, 'Z', annotation brackets), which stay case-sensitive.
    private static boolean isDesignator(char c, char designator) {
        return Character.toUpperCase(c) == designator;
    }

    // TemporalDecimalFraction is bounded to 1-9 digits; a 10th digit is a Range/syntax error, not a
    // value to silently truncate.
    private static String readFractionDigits(Cursor cursor) {
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

    // Every Temporal string sign (extended year, duration overall sign, UTC offset) is ASCII-only;
    // U+2212 MINUS SIGN, though visually similar, is rejected everywhere per test262.
    private static boolean isSign(char c) {
        return c == '+' || c == '-';
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
