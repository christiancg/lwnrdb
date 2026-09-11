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

    // "UTC" is matched ASCII-case-insensitively (java.time's ZoneId.of is not) and always
    // canonicalizes to uppercase "UTC" - unlike an arbitrary IANA name, which stays case-sensitive.
    @Test
    public void test_time_zone_utc_is_case_insensitive() {
        assertEquals("UTC", str("new Temporal.ZonedDateTime(0n, 'utc').timeZoneId"));
        assertEquals(0, num("new Temporal.ZonedDateTime(0n, 'utc').offsetNanoseconds"));
        assertEquals("UTC", str("new Temporal.ZonedDateTime(0n, 'Utc').timeZoneId"));
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
    public void test_zone_offset_parsing_variants() {
        assertEquals("+00:00", str("new Temporal.ZonedDateTime(0n, 'Z').offset"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, '+99:99')"));
    }

    @Test
    public void test_unknown_method_returns_undefined() {
        assertTrue(bool("typeof new Temporal.ZonedDateTime(0n, 'UTC').notAMethod === 'undefined'"));
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

    // era/eraYear are always undefined for the ISO-8601-only calendar this engine implements
    @Test
    public void test_era_and_era_year_are_undefined() {
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').era === undefined"));
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').eraYear === undefined"));
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
    public void test_reflect_construct_subclass_prototype() {
        assertTrue(bool("class Sub extends Temporal.ZonedDateTime {}"
                + "const z = Reflect.construct(Temporal.ZonedDateTime, [0n, 'UTC'], Sub);"
                + "Object.getPrototypeOf(z) === Sub.prototype"));
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

    @Test
    public void test_get_time_zone_transition_accepts_property_bag() {
        final var instance = "new Temporal.ZonedDateTime(0n, 'America/New_York')";
        assertTrue(bool(instance + ".getTimeZoneTransition({direction: 'next'}) !== null"));
    }

    @Test
    public void test_get_time_zone_transition_rejects_wrong_type() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').getTimeZoneTransition(5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').getTimeZoneTransition(undefined)"));
    }

    @Test
    public void test_start_of_day_method() {
        assertEquals("1970-01-01T00:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(500000000n, 'UTC').startOfDay().toString()"));
    }

    @Test
    public void test_to_string_fractional_second_digits_nan_throws() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').toString({fractionalSecondDigits: NaN})"));
    }

    @Test
    public void test_to_string_fractional_second_digits_invalid_string_throws() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.ZonedDateTime(0n, 'UTC').toString({fractionalSecondDigits: 'bogus'})"));
    }

    @Test
    public void test_to_string_smallest_unit_plural_accepted() {
        assertEquals("1970-01-01T00:00:00.500+00:00[UTC]",
                str("new Temporal.ZonedDateTime(500000000n, 'UTC').toString({smallestUnit: 'milliseconds'})"));
    }
}
