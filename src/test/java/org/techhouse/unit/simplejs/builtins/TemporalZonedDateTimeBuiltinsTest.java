package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TemporalZonedDateTimeBuiltinsTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static ZoneOffsetTransition findTransition(boolean gap) {
        var t = NEW_YORK.getRules().nextTransition(Instant.parse("2024-01-01T00:00:00Z"));
        while (t != null && t.isGap() != gap) {
            t = NEW_YORK.getRules().nextTransition(t.getInstant());
        }
        if (t == null) {
            throw new IllegalStateException("No " + (gap ? "gap" : "fold") + " transition found for test fixture");
        }
        return t;
    }

    private static String midpointArgs(ZoneOffsetTransition transition) {
        final var mid = transition.getDateTimeBefore()
                .plus(Duration.between(transition.getDateTimeBefore(), transition.getDateTimeAfter()).dividedBy(2));
        return "year: " + mid.getYear() + ", month: " + mid.getMonthValue() + ", day: " + mid.getDayOfMonth()
                + ", hour: " + mid.getHour() + ", minute: " + mid.getMinute();
    }

    @Test
    public void test_constructor_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.ZonedDateTime(0n, 'UTC')"));
    }

    @Test
    public void test_constructor_requires_bigint_epoch() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0, 'UTC')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime()"));
    }

    @Test
    public void test_constructor_requires_time_zone() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n)"));
    }

    @Test
    public void test_constructor_rejects_invalid_time_zone() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'Not/AZone')"));
    }

    @Test
    public void test_constructor_rejects_non_iso8601_calendar() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC', 'hebrew')"));
    }

    // A non-string timeZone/calendar argument is a TypeError, not a RangeError
    @Test
    public void test_constructor_non_string_arguments_are_type_errors() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC', 5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').withCalendar(5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').withTimeZone(5)"));
    }

    // "UTC" is matched ASCII-case-insensitively (java.time's ZoneId.of is not) and always
    // canonicalizes to uppercase "UTC" - unlike an arbitrary IANA name, which stays case-sensitive.
    @Test
    public void test_time_zone_utc_is_case_insensitive() {
        assertEquals("UTC", str("new Temporal.ZonedDateTime(0n, 'utc').timeZoneId"));
        assertEquals(0, num("new Temporal.ZonedDateTime(0n, 'utc').offsetNanoseconds"));
        assertEquals("UTC", str("new Temporal.ZonedDateTime(0n, 'Utc').timeZoneId"));
    }

    // A calendar annotation on an ISO string parsed by from() is validated the same way a bare
    // identifier is
    @Test
    public void test_from_string_validates_calendar_annotation() {
        assertEquals("iso8601",
                str("Temporal.ZonedDateTime.from('1970-01-01T00:00:00+00:00[UTC][u-ca=iso8601]').calendarId"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from('1970-01-01T00:00:00+00:00[UTC][u-ca=hebrew]')"));
    }

    // The property-bag `timeZone` field accepts a full ISO date-time string carrying a bracketed
    // time zone (ToTemporalTimeZoneIdentifier's flexible form), not just a bare identifier
    @Test
    public void test_from_fields_time_zone_field_flexible() {
        assertEquals("America/New_York", str("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, "
                + "timeZone: '2020-01-01T00:00:00-05:00[America/New_York]'}).timeZoneId"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 5})"));
    }

    // The property-bag `calendar` field accepts a bare identifier, a full ISO string carrying (or
    // defaulting) a u-ca annotation, or a Temporal object (fast path)
    @Test
    public void test_from_fields_calendar_field_flexible() {
        assertEquals("iso8601",
                str("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', calendar: 'iso8601'})"
                        + ".calendarId"));
        assertEquals("iso8601", str("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', "
                + "calendar: '2020-06-15[u-ca=iso8601]'}).calendarId"));
        assertEquals("iso8601", str("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', "
                + "calendar: new Temporal.PlainDate(2020, 1, 1)}).calendarId"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run(
                "Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', calendar: 'hebrew'})"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', calendar: 5})"));
    }

    @Test
    public void test_constructor_accepts_offset_time_zone() {
        assertEquals("+01:00", str("new Temporal.ZonedDateTime(0n, '+01:00').timeZoneId"));
        assertEquals(1, num("new Temporal.ZonedDateTime(0n, '+01:00').hour"));
    }

    @Test
    public void test_from_accepts_instance_string_and_object() {
        assertEquals("UTC", str("Temporal.ZonedDateTime.from(new Temporal.ZonedDateTime(0n, 'UTC')).timeZoneId"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]",
                str("Temporal.ZonedDateTime.from('1970-01-01T00:00:00+00:00[UTC]').toString()"));
        assertEquals(2020, num("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC'}).year"));
    }

    @Test
    public void test_from_rejects_invalid() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.ZonedDateTime.from(42)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15})"));
    }

    @Test
    public void test_compare() {
        assertEquals(-1, num("Temporal.ZonedDateTime.compare(new Temporal.ZonedDateTime(0n, 'UTC'), "
                + "new Temporal.ZonedDateTime(1n, 'UTC'))"));
        assertEquals(0, num("Temporal.ZonedDateTime.compare(new Temporal.ZonedDateTime(0n, 'UTC'), "
                + "new Temporal.ZonedDateTime(0n, '+01:00'))"));
    }

    @Test
    public void test_field_accessors() {
        assertEquals("2020,6,15,10,30,15,500,250,125",
                str("var z = new Temporal.ZonedDateTime("
                        + "Temporal.Instant.from('2020-06-15T10:30:15.500250125Z').epochNanoseconds, 'UTC');"
                        + "z.year+','+z.month+','+z.day+','+z.hour+','+z.minute+','+z.second+','+z.millisecond+','"
                        + "+z.microsecond+','+z.nanosecond"));
        assertEquals("M06", str("new Temporal.ZonedDateTime(0n, 'UTC').with({month: 6}).monthCode"));
    }

    @Test
    public void test_week_and_calendar_accessors() {
        assertEquals("iso8601", str("new Temporal.ZonedDateTime(0n, 'UTC').calendarId"));
        assertEquals(4, num("new Temporal.ZonedDateTime(0n, 'UTC').dayOfWeek"));
        assertEquals(1, num("new Temporal.ZonedDateTime(0n, 'UTC').dayOfYear"));
        assertEquals(7, num("new Temporal.ZonedDateTime(0n, 'UTC').daysInWeek"));
        assertEquals(31, num("new Temporal.ZonedDateTime(0n, 'UTC').daysInMonth"));
        assertEquals(365, num("new Temporal.ZonedDateTime(0n, 'UTC').daysInYear"));
        assertEquals(12, num("new Temporal.ZonedDateTime(0n, 'UTC').monthsInYear"));
        assertTrue(bool("new Temporal.ZonedDateTime(Temporal.Instant.from('2020-01-01T00:00:00Z')"
                + ".epochNanoseconds, 'UTC').inLeapYear"));
    }

    @Test
    public void test_time_zone_and_epoch_accessors() {
        assertEquals("UTC", str("new Temporal.ZonedDateTime(0n, 'UTC').timeZoneId"));
        assertEquals(0, num("new Temporal.ZonedDateTime(0n, 'UTC').epochMilliseconds"));
        assertEquals("0", str("new Temporal.ZonedDateTime(0n, 'UTC').epochNanoseconds.toString()"));
        assertEquals("1000000", str("new Temporal.ZonedDateTime(1000000n, 'UTC').epochNanoseconds.toString()"));
    }

    @Test
    public void test_offset_accessors() {
        assertEquals("+01:00", str("new Temporal.ZonedDateTime(0n, '+01:00').offset"));
        assertEquals(3_600_000_000_000.0, num("new Temporal.ZonedDateTime(0n, '+01:00').offsetNanoseconds"));
        assertEquals("+00:00", str("new Temporal.ZonedDateTime(0n, 'UTC').offset"));
    }

    @Test
    public void test_hours_in_day() {
        assertEquals(24, num("new Temporal.ZonedDateTime(0n, 'UTC').hoursInDay"));
        final var gap = findTransition(true);
        final var beforeMidnightNanos = gap.getDateTimeBefore().toLocalDate().atStartOfDay(NEW_YORK).toEpochSecond()
                * 1_000_000_000L;
        assertEquals(23,
                num("new Temporal.ZonedDateTime(" + beforeMidnightNanos + "n, 'America/New_York').hoursInDay"));
        final var fold = findTransition(false);
        final var foldMidnightNanos = fold.getDateTimeBefore().toLocalDate().atStartOfDay(NEW_YORK).toEpochSecond()
                * 1_000_000_000L;
        assertEquals(25, num("new Temporal.ZonedDateTime(" + foldMidnightNanos + "n, 'America/New_York').hoursInDay"));
    }

    @Test
    public void test_with() {
        assertEquals(2021, num("new Temporal.ZonedDateTime(0n, 'UTC').with({year: 2021}).year"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').with(42)"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("new Temporal.ZonedDateTime(0n, 'UTC').with(new Temporal.ZonedDateTime(0n, 'UTC'))"));
    }

    @Test
    public void test_with_calendar() {
        assertEquals("iso8601", str("new Temporal.ZonedDateTime(0n, 'UTC').withCalendar('iso8601').calendarId"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').withCalendar('hebrew')"));
    }

    @Test
    public void test_with_time_zone() {
        assertEquals("Europe/London",
                str("new Temporal.ZonedDateTime(0n, 'UTC').withTimeZone('Europe/London').timeZoneId"));
        assertEquals(0, num("new Temporal.ZonedDateTime(0n, 'UTC').withTimeZone('Europe/London').epochMilliseconds"));
    }

    @Test
    public void test_with_plain_date() {
        assertEquals("2021-01-02T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(0n, 'UTC')"
                + ".withPlainDate(new Temporal.PlainDate(2021, 1, 2)).toString()"));
        assertEquals("2021-01-02T00:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').withPlainDate('2021-01-02').toString()"));
    }

    @Test
    public void test_with_plain_time() {
        assertEquals("1970-01-01T11:30:00+00:00[UTC]", str(
                "new Temporal.ZonedDateTime(0n, 'UTC')" + ".withPlainTime(new Temporal.PlainTime(11, 30)).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').withPlainTime().toString()"));
    }

    @Test
    public void test_add_subtract_basic() {
        assertEquals(2, num("new Temporal.ZonedDateTime(0n, 'UTC').add({hours: 2}).hour"));
        assertEquals(22, num("new Temporal.ZonedDateTime(0n, 'UTC').subtract({hours: 2}).hour"));
        assertEquals(2, num("new Temporal.ZonedDateTime(0n, 'UTC').add(Temporal.Duration.from({days: 1})).day"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').add(42)"));
    }

    // A duration-like object with none of the ten recognized properties present is a TypeError
    @Test
    public void test_add_rejects_duration_like_with_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').add({})"));
    }

    @Test
    public void test_add_across_dst_gap_calendar_vs_exact_time() {
        final var gap = findTransition(true);
        final var localDate = gap.getDateTimeBefore().toLocalDate();
        final var startOfDayNanos = localDate.atStartOfDay(NEW_YORK).toEpochSecond() * 1_000_000_000L;
        // Adding 1 calendar day lands at the same local wall-clock time the next day (constrained by
        // the zone's real rules), while adding 24 exact hours lands 1 hour later across the gap.
        final var script = "var z = new Temporal.ZonedDateTime(" + startOfDayNanos + "n, 'America/New_York');"
                + "var byDay = z.add({days: 1});" + "var byHours = z.add({hours: 24});"
                + "byDay.hour + ',' + byHours.hour";
        assertEquals("0,1", str(script));
    }

    @Test
    public void test_until_since_basic() {
        assertEquals(2, num("new Temporal.ZonedDateTime(0n, 'UTC')"
                + ".until(new Temporal.ZonedDateTime(7200000000000n, 'UTC'), {largestUnit: 'hour'}).hours"));
        assertEquals(-2, num("new Temporal.ZonedDateTime(0n, 'UTC')"
                + ".since(new Temporal.ZonedDateTime(7200000000000n, 'UTC'), {largestUnit: 'hour'}).hours"));
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC')"
                + ".until(new Temporal.ZonedDateTime(7200000000000n, 'UTC')) instanceof Temporal.Duration"));
    }

    // until()/since() round a computed Duration, so a "day" increment greater than 1 is valid when
    // largestUnit is not larger than "day" (unlike round(), which only ever accepts 1)
    @Test
    public void test_until_allows_day_increment_greater_than_one() {
        final var tenDaysNanos = 10L * 24 * 3_600_000_000_000L;
        assertEquals(10,
                num("new Temporal.ZonedDateTime(0n, 'UTC')" + ".until(new Temporal.ZonedDateTime(" + tenDaysNanos
                        + "n, 'UTC'), " + "{smallestUnit: 'day', largestUnit: 'day', roundingIncrement: 10, "
                        + "roundingMode: 'floor'}).days"));
    }

    // Calendar-unit differencing (largestUnit above "day") is implemented via RelativeDurationMath on
    // the receiver's local wall-clock date+time - no RangeError, a real months/hours breakdown
    // instead (the two instants are two hours apart on the same calendar day, so months stays 0).
    @Test
    public void test_until_since_calendar_units() {
        assertEquals("0,2",
                str("var d = new Temporal.ZonedDateTime(0n, 'UTC')"
                        + ".until(new Temporal.ZonedDateTime(7200000000000n, 'UTC'), {largestUnit: 'month'});"
                        + "d.months + ',' + d.hours"));
    }

    @Test
    public void test_until_across_dst_day_boundary() {
        final var gap = findTransition(true);
        final var localDate = gap.getDateTimeBefore().toLocalDate();
        final var startOfDayNanos = localDate.minusDays(1).atStartOfDay(NEW_YORK).toEpochSecond() * 1_000_000_000L;
        final var startOfNextDayNanos = localDate.atStartOfDay(NEW_YORK).toEpochSecond() * 1_000_000_000L;
        final var script = "new Temporal.ZonedDateTime(" + startOfDayNanos + "n, 'America/New_York')"
                + ".until(new Temporal.ZonedDateTime(" + startOfNextDayNanos + "n, 'America/New_York'), "
                + "{largestUnit: 'day'}).days";
        assertEquals(1, num(script));
    }

    @Test
    public void test_round() {
        assertEquals("1970-01-01T01:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(1800000000000n, 'UTC').round({smallestUnit: 'hour'}).toString()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').round()"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').round({smallestUnit: 'year'})"));
    }

    @Test
    public void test_round_to_day_is_zone_aware() {
        final var gap = findTransition(true);
        final var localDate = gap.getDateTimeBefore().toLocalDate();
        final var noonNanos = localDate.atTime(12, 0).atZone(NEW_YORK).toEpochSecond() * 1_000_000_000L;
        final var startOfDayNanos = localDate.atStartOfDay(NEW_YORK).toEpochSecond() * 1_000_000_000L;
        final var startOfNextDayNanos = localDate.plusDays(1).atStartOfDay(NEW_YORK).toEpochSecond() * 1_000_000_000L;
        final var roundedMillis = num("new Temporal.ZonedDateTime(" + noonNanos + "n, 'America/New_York')"
                + ".round({smallestUnit: 'day'}).epochMilliseconds");
        final var startOfDayMillis = startOfDayNanos / 1_000_000L;
        final var startOfNextDayMillis = startOfNextDayNanos / 1_000_000L;
        assertTrue(roundedMillis == (double) startOfDayMillis || roundedMillis == (double) startOfNextDayMillis);
    }

    @Test
    public void test_equals() {
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').equals(new Temporal.ZonedDateTime(0n, 'UTC'))"));
        assertTrue(bool("!new Temporal.ZonedDateTime(0n, 'UTC').equals(new Temporal.ZonedDateTime(0n, '+00:00'))"));
    }

    @Test
    public void test_conversions_return_real_instances() {
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').toInstant() instanceof Temporal.Instant"));
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').toPlainDate() instanceof Temporal.PlainDate"));
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').toPlainTime() instanceof Temporal.PlainTime"));
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').toPlainDateTime() instanceof Temporal.PlainDateTime"));
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').toPlainYearMonth() instanceof Temporal.PlainYearMonth"));
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').toPlainMonthDay() instanceof Temporal.PlainMonthDay"));
        assertEquals("1970-01-01", str("new Temporal.ZonedDateTime(0n, 'UTC').toPlainDate().toString()"));
    }

    @Test
    public void test_to_string_variants() {
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(0n, 'UTC').toString()"));
        assertEquals("1970-01-01T00:00:00[UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').toString({offset: 'never'})"));
        assertEquals("1970-01-01T00:00:00[u-ca=iso8601]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').toString({offset: 'never', timeZoneName: 'never', "
                        + "calendarName: 'always'})"));
        assertEquals("1970-01-01T00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').toString({smallestUnit: 'minute'})"));
        assertEquals("1970-01-01T00:00:00.500+00:00[UTC]",
                str("new Temporal.ZonedDateTime(500000000n, 'UTC').toString({fractionalSecondDigits: 3})"));
    }

    @Test
    public void test_to_json_and_to_locale_string() {
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(0n, 'UTC').toJSON()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(0n, 'UTC').toLocaleString()"));
        assertEquals("\"1970-01-01T00:00:00+00:00[UTC]\"",
                str("JSON.stringify(new Temporal.ZonedDateTime(0n, 'UTC'))"));
    }

    @Test
    public void test_get_iso_fields() {
        final var script = "var f = new Temporal.ZonedDateTime(0n, 'UTC').getISOFields();"
                + "f.calendar + ',' + f.isoYear + ',' + f.isoMonth + ',' + f.isoDay + ',' + f.offset + ',' "
                + "+ f.timeZone";
        assertEquals("iso8601,1970,1,1,+00:00,UTC", str(script));
    }

    @Test
    public void test_get_time_zone_transition() {
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'America/New_York')"
                + ".getTimeZoneTransition('next') instanceof Temporal.ZonedDateTime"));
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').getTimeZoneTransition('next') === null"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.ZonedDateTime(0n, 'America/New_York').getTimeZoneTransition('sideways')"));
    }

    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("+new Temporal.ZonedDateTime(0n, 'UTC')"));
    }

    @Test
    public void test_brand_check() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.prototype.toString.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Object.getOwnPropertyDescriptor(Temporal.ZonedDateTime.prototype, 'year').get.call({})"));
    }

    @Test
    public void test_disambiguation_gap_all_modes() {
        final var gap = findTransition(true);
        final var fields = "{" + midpointArgs(gap) + ", timeZone: 'America/New_York'}";
        final var before = gap.getOffsetBefore().getTotalSeconds();
        final var after = gap.getOffsetAfter().getTotalSeconds();
        // A gap's two candidate instants both land on the "wrong" side of the real transition once
        // rendered back: applying the pre-transition offset lands past the transition (observed
        // offsetAfter - "compatible"/"later", the forward-shift every engine uses for a nonexistent
        // local time), applying the post-transition offset lands before it (observed offsetBefore -
        // "earlier"). Verified via the actually-observed offset rather than a hardcoded hour, so this
        // stays correct across tzdb updates.
        assertEquals(after, (int) num("Temporal.ZonedDateTime.from(" + fields
                + ", {disambiguation: 'compatible'}).offsetNanoseconds / 1000000000"));
        assertEquals(after, (int) num("Temporal.ZonedDateTime.from(" + fields
                + ", {disambiguation: 'later'}).offsetNanoseconds / 1000000000"));
        assertEquals(before, (int) num("Temporal.ZonedDateTime.from(" + fields
                + ", {disambiguation: 'earlier'}).offsetNanoseconds / 1000000000"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from(" + fields + ", {disambiguation: 'reject'})"));
    }

    @Test
    public void test_disambiguation_fold_all_modes() {
        final var fold = findTransition(false);
        final var fields = "{" + midpointArgs(fold) + ", timeZone: 'America/New_York'}";
        final var before = fold.getOffsetBefore().getTotalSeconds();
        final var after = fold.getOffsetAfter().getTotalSeconds();
        // A fold's two candidate instants are both real (one before, one after the transition), so
        // the applied offset matches the observed one directly: "compatible"/"earlier" pick the first
        // (offsetBefore) occurrence, "later" picks the second (offsetAfter).
        assertEquals(before, (int) num("Temporal.ZonedDateTime.from(" + fields
                + ", {disambiguation: 'compatible'}).offsetNanoseconds / 1000000000"));
        assertEquals(before, (int) num("Temporal.ZonedDateTime.from(" + fields
                + ", {disambiguation: 'earlier'}).offsetNanoseconds / 1000000000"));
        assertEquals(after, (int) num("Temporal.ZonedDateTime.from(" + fields
                + ", {disambiguation: 'later'}).offsetNanoseconds / 1000000000"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from(" + fields + ", {disambiguation: 'reject'})"));
    }

    @Test
    public void test_disambiguation_rejects_invalid_option() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, month: 1, day: 1, timeZone: 'UTC'}, "
                        + "{disambiguation: 'sideways'})"));
    }

    @Test
    public void test_reflect_construct_uses_new_target_prototype() {
        final var script = "var Ctor = function() {};" + "var z = Reflect.construct(Temporal.ZonedDateTime, "
                + "[0n, 'UTC'], Ctor);"
                + "(Object.getPrototypeOf(z) === Ctor.prototype) + ',' + z.year + ',' + z.weekOfYear + ','"
                + " + z.yearOfWeek";
        assertEquals("true,1970,1,1970", str(script));
    }

    @Test
    public void test_from_accepts_wrapped_instance() {
        final var script = "var Ctor = function() {};"
                + "var z = Reflect.construct(Temporal.ZonedDateTime, [0n, 'UTC'], Ctor);"
                + "var copy = Temporal.ZonedDateTime.from(z);"
                + "copy.timeZoneId + ',' + (Object.getPrototypeOf(copy) === Ctor.prototype)";
        assertEquals("UTC,false", str(script));
    }

    @Test
    public void test_constructor_accepts_explicit_iso8601_calendar() {
        assertEquals("iso8601", str("new Temporal.ZonedDateTime(0n, 'UTC', 'iso8601').calendarId"));
    }

    @Test
    public void test_zone_offset_parsing_variants() {
        assertEquals("+00:00", str("new Temporal.ZonedDateTime(0n, 'Z').offset"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, '+99:99')"));
    }

    @Test
    public void test_unknown_method_returns_undefined() {
        assertTrue(bool("typeof new Temporal.ZonedDateTime(0n, 'UTC').notAMethod === 'undefined'"));
    }

    @Test
    public void test_from_fields_requires_year_and_day() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({month: 1, day: 1, timeZone: 'UTC'})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, month: 1, timeZone: 'UTC'})"));
    }

    @Test
    public void test_from_fields_month_code() {
        assertEquals(6,
                num("Temporal.ZonedDateTime.from({year: 2020, monthCode: 'M06', day: 1, timeZone: 'UTC'}).month"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year: 2020, monthCode: 'M06', month: 7, day: 1, timeZone: 'UTC'})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, day: 1, timeZone: 'UTC'})"));
    }

    @Test
    public void test_with_month_code() {
        assertEquals(6, num("new Temporal.ZonedDateTime(0n, 'UTC').with({monthCode: 'M06'}).month"));
    }

    @Test
    public void test_invalid_month_code_variants() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year: 2020, monthCode: 'MXX', day: 1, timeZone: 'UTC'})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year: 2020, monthCode: 'M13', day: 1, timeZone: 'UTC'})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year: 2020, monthCode: 'X06', day: 1, timeZone: 'UTC'})"));
    }

    @Test
    public void test_integer_field_rejects_non_finite() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: NaN, month: 1, day: 1, timeZone: 'UTC'})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year: Infinity, month: 1, day: 1, timeZone: 'UTC'})"));
    }

    @Test
    public void test_reject_overflow_validates_time_fields() {
        assertEquals(23,
                num("Temporal.ZonedDateTime.from({year: 2020, month: 1, day: 1, hour: 23, minute: 59, "
                        + "second: 59, millisecond: 999, microsecond: 999, nanosecond: 999, timeZone: 'UTC'}, "
                        + "{overflow: 'reject'}).hour"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, "
                + "month: 1, day: 1, hour: 24, timeZone: 'UTC'}, {overflow: 'reject'})"));
    }

    @Test
    public void test_options_must_be_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year: 2020, month: 1, day: 1, timeZone: 'UTC'}, " + "42)"));
    }

    @Test
    public void test_rounding_increment_option() {
        assertEquals("1970-01-01T00:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').round({smallestUnit: 'hour', "
                        + "roundingIncrement: 2}).toString()"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.ZonedDateTime(0n, 'UTC').round({smallestUnit: 'hour', roundingIncrement: 0})"));
    }

    // "expand" rounds away from zero unconditionally, unlike "halfExpand"'s tie-breaking
    @Test
    public void test_rounding_mode_expand() {
        assertEquals("1970-01-01T01:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(1000000000n, 'UTC').round({smallestUnit: 'hour', "
                        + "roundingMode: 'expand'}).toString()"));
    }

    // 1250 is exactly halfway between 1000 and 1500 (increment 500); halfEven picks the even
    // multiple (1000/500 = 2, even).
    @Test
    public void test_rounding_mode_half_even_at_an_exact_tie() {
        assertEquals("1000", str("new Temporal.ZonedDateTime(1250n, 'UTC').round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 500, roundingMode: 'halfEven'}).epochNanoseconds.toString()"));
    }

    // A negative-direction halfCeil/halfFloor tie: the tie-break can go toward the unchanged
    // (non-away-from-zero) quotient depending on sign and direction.
    @Test
    public void test_rounding_mode_half_ceil_half_floor_negative_ties() {
        assertEquals("-1000", str("new Temporal.ZonedDateTime(-1250n, 'UTC').round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 500, roundingMode: 'halfCeil'}).epochNanoseconds.toString()"));
        assertEquals("1000", str("new Temporal.ZonedDateTime(1250n, 'UTC').round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 500, roundingMode: 'halfFloor'}).epochNanoseconds.toString()"));
    }

    // era/eraYear are always undefined for the ISO-8601-only calendar this engine implements
    @Test
    public void test_era_and_era_year_are_undefined() {
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').era === undefined"));
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').eraYear === undefined"));
    }

    @Test
    public void test_rounding_increment_validation_for_day_and_ms() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.ZonedDateTime(0n, 'UTC').round({smallestUnit: 'day', roundingIncrement: 2})"));
        assertEquals("1970-01-01T00:00:00.250+00:00[UTC]",
                str("new Temporal.ZonedDateTime(250000000n, 'UTC').round({smallestUnit: 'millisecond', "
                        + "roundingIncrement: 250}).toString({fractionalSecondDigits: 3})"));
    }

    @Test
    public void test_since_negates_rounding_mode_ceil_floor() {
        assertEquals(3,
                num("new Temporal.ZonedDateTime(7200000000000n, 'UTC').since("
                        + "new Temporal.ZonedDateTime(0n, 'UTC'), {smallestUnit: 'hour', roundingIncrement: 3, "
                        + "roundingMode: 'ceil'}).hours"));
        assertEquals(0.0,
                num("new Temporal.ZonedDateTime(7200000000000n, 'UTC').since("
                        + "new Temporal.ZonedDateTime(0n, 'UTC'), {smallestUnit: 'hour', roundingIncrement: 3, "
                        + "roundingMode: 'floor'}).hours"),
                0.0);
        assertEquals(3,
                num("new Temporal.ZonedDateTime(7200000000000n, 'UTC').since("
                        + "new Temporal.ZonedDateTime(0n, 'UTC'), {smallestUnit: 'hour', roundingIncrement: 3, "
                        + "roundingMode: 'halfCeil'}).hours"));
        assertEquals(3,
                num("new Temporal.ZonedDateTime(7200000000000n, 'UTC').since("
                        + "new Temporal.ZonedDateTime(0n, 'UTC'), {smallestUnit: 'hour', roundingIncrement: 3, "
                        + "roundingMode: 'halfFloor'}).hours"));
    }

    @Test
    public void test_round_accepts_string_shorthand() {
        assertEquals("1970-01-01T01:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(1800000000000n, 'UTC').round('hour').toString()"));
    }

    @Test
    public void test_with_plain_date_accepts_wrapped_instance_and_rejects_invalid() {
        final var script = "var Ctor = function() {};"
                + "var d = Reflect.construct(Temporal.PlainDate, [2021, 1, 2], Ctor);"
                + "new Temporal.ZonedDateTime(0n, 'UTC').withPlainDate(d).toString()";
        assertEquals("2021-01-02T00:00:00+00:00[UTC]", str(script));
        assertEquals("2021-06-15T00:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').withPlainDate({year: 2021, month: 6, day: 15}).toString()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').withPlainDate(42)"));
    }

    @Test
    public void test_with_plain_time_accepts_wrapped_instance_string_and_object() {
        final var script = "var Ctor = function() {};" + "var t = Reflect.construct(Temporal.PlainTime, [5, 15], Ctor);"
                + "new Temporal.ZonedDateTime(0n, 'UTC').withPlainTime(t).toString()";
        assertEquals("1970-01-01T05:15:00+00:00[UTC]", str(script));
        assertEquals("1970-01-01T08:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').withPlainTime('08:00').toString()"));
        assertEquals("1970-01-01T09:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').withPlainTime({hour: 9}).toString()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').withPlainTime({})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').withPlainTime(42)"));
    }

    @Test
    public void test_add_subtract_accept_duration_string_and_reject_non_integer() {
        assertEquals(2, num("new Temporal.ZonedDateTime(0n, 'UTC').add('P1D').day"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').add({hours: 1.5})"));
    }

    @Test
    public void test_get_time_zone_transition_previous() {
        assertTrue(bool("new Temporal.ZonedDateTime(" + Instant.parse("2030-01-01T00:00:00Z").getEpochSecond()
                + "000000000n, 'America/New_York').getTimeZoneTransition('previous') instanceof "
                + "Temporal.ZonedDateTime"));
    }

    @Test
    public void test_to_string_time_zone_name_critical() {
        assertEquals("1970-01-01T00:00+00:00[!UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').toString({smallestUnit: 'minute', "
                        + "timeZoneName: 'critical'})"));
    }

    @Test
    public void test_to_string_rejects_out_of_range_fractional_digits() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').toString({fractionalSecondDigits: 10})"));
    }

    @Test
    public void test_equality_operator_compares_zoned_date_times() {
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC') !== new Temporal.ZonedDateTime(0n, 'UTC')"));
        assertTrue(bool("var z = new Temporal.ZonedDateTime(0n, 'UTC'); z === z"));
    }

    @Test
    public void test_string_coercion() {
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("String(new Temporal.ZonedDateTime(0n, 'UTC'))"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("`${new Temporal.ZonedDateTime(0n, 'UTC')}`"));
    }

    @Test
    public void test_z_offset_is_utc() {
        assertTrue(bool("Temporal.ZonedDateTime.from('1970-01-01T00:00:00Z[UTC]').epochNanoseconds === 0n"));
    }

    @Test
    public void test_offset_with_fractional_part_is_trimmed() {
        assertEquals(0, num("Temporal.ZonedDateTime.from('1970-01-01T00:00:00+00:00:00.5[+00:00]').offsetNanoseconds"));
    }

    @Test
    public void test_reflect_construct_subclass_prototype() {
        assertTrue(bool("class Sub extends Temporal.ZonedDateTime {}"
                + "const z = Reflect.construct(Temporal.ZonedDateTime, [0n, 'UTC'], Sub);"
                + "Object.getPrototypeOf(z) === Sub.prototype"));
    }

    @Test
    public void test_from_string_invalid_time_zone_throws_range_error() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from('2020-06-15T00:00:00[Not/AZone]')"));
    }

    @Test
    public void test_until_rejects_smallest_unit_larger_than_largest_unit() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').until(new Temporal.ZonedDateTime("
                        + "86400000000000n, 'UTC'), {smallestUnit: 'hour', largestUnit: 'minute'})"));
    }

    @Test
    public void test_round_rejects_non_object_non_string_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').round(5)"));
    }

    @Test
    public void test_round_requires_smallest_unit() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').round({})"));
    }

    @Test
    public void test_round_to_calendar_day_with_various_modes() {
        // 12:00 UTC is exactly half of a 24h UTC day.
        assertEquals("1970-01-02T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(43200000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'ceil'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(43200000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'floor'}).toString()"));
        assertEquals("1970-01-02T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(43200000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'halfCeil'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(43200000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'halfFloor'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(43200000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'halfEven'}).toString()"));
        // A quarter into the day rounds down under every half-based mode (not a tie).
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(21600000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'halfTrunc'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(21600000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'trunc'}).toString()"));
        assertEquals("1970-01-02T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(21600000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'expand'}).toString()"));
    }

    @Test
    public void test_round_signed_nanoseconds_negative_epoch() {
        // -30 minutes (half of an hour) before epoch, exercising the sign<0 branches.
        final var halfHourNs = "-1800000000000n";
        assertEquals("1969-12-31T23:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(" + halfHourNs
                + ", 'UTC').round(" + "{smallestUnit: 'hour', roundingMode: 'floor'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(" + halfHourNs
                + ", 'UTC').round(" + "{smallestUnit: 'hour', roundingMode: 'ceil'}).toString()"));
        assertEquals("1969-12-31T23:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(" + halfHourNs
                + ", 'UTC').round(" + "{smallestUnit: 'hour', roundingMode: 'halfFloor'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(" + halfHourNs
                + ", 'UTC').round(" + "{smallestUnit: 'hour', roundingMode: 'halfCeil'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(" + halfHourNs
                + ", 'UTC').round(" + "{smallestUnit: 'hour', roundingMode: 'halfEven'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(" + halfHourNs
                + ", 'UTC').round(" + "{smallestUnit: 'hour', roundingMode: 'trunc'}).toString()"));
        assertEquals("1969-12-31T23:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(" + halfHourNs
                + ", 'UTC').round(" + "{smallestUnit: 'hour', roundingMode: 'expand'}).toString()"));
    }

    @Test
    public void test_to_string_smallest_unit_second() {
        assertEquals("1970-01-01T00:00:30", str("new Temporal.ZonedDateTime(30500000000n, 'UTC').toString("
                + "{smallestUnit: 'second', timeZoneName: 'never', offset: 'never'})"));
    }

    @Test
    public void test_to_string_rejects_smallest_unit_larger_than_second() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').toString({smallestUnit: 'hour'})"));
    }

    @Test
    public void test_to_string_numeric_fractional_second_digits() {
        assertEquals("1970-01-01T00:00:00.500+00:00[UTC]",
                str("new Temporal.ZonedDateTime(500000000n, 'UTC').toString({fractionalSecondDigits: 3})"));
    }
}
