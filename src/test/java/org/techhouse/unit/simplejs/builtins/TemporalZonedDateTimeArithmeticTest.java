package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.test.JsEval;

public class TemporalZonedDateTimeArithmeticTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static ZoneOffsetTransition findGapTransition() {
        var t = NEW_YORK.getRules().nextTransition(Instant.parse("2024-01-01T00:00:00Z"));
        while (t != null && !t.isGap()) {
            t = NEW_YORK.getRules().nextTransition(t.getInstant());
        }
        if (t == null) {
            throw new IllegalStateException("No gap transition found for test fixture");
        }
        return t;
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

    @Test
    public void test_add_rejects_duration_like_with_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').add({})"));
    }

    @Test
    public void test_add_across_dst_gap_calendar_vs_exact_time() {
        final var gap = findGapTransition();
        final var localDate = gap.getDateTimeBefore().toLocalDate();
        final var startOfDayNanos = localDate.atStartOfDay(NEW_YORK).toEpochSecond() * 1_000_000_000L;
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
        assertTrue(JsEval.bool("new Temporal.ZonedDateTime(0n, 'UTC')"
                + ".until(new Temporal.ZonedDateTime(7200000000000n, 'UTC')) instanceof Temporal.Duration"));
    }

    @Test
    public void test_until_allows_day_increment_greater_than_one() {
        final var tenDaysNanos = 10L * 24 * 3_600_000_000_000L;
        assertEquals(10,
                num("new Temporal.ZonedDateTime(0n, 'UTC')" + ".until(new Temporal.ZonedDateTime(" + tenDaysNanos
                        + "n, 'UTC'), " + "{smallestUnit: 'day', largestUnit: 'day', roundingIncrement: 10, "
                        + "roundingMode: 'floor'}).days"));
    }

    @Test
    public void test_until_since_calendar_units() {
        assertEquals("0,2",
                str("var d = new Temporal.ZonedDateTime(0n, 'UTC')"
                        + ".until(new Temporal.ZonedDateTime(7200000000000n, 'UTC'), {largestUnit: 'month'});"
                        + "d.months + ',' + d.hours"));
    }

    @Test
    public void test_until_across_dst_day_boundary() {
        final var gap = findGapTransition();
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
        final var gap = findGapTransition();
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
    public void test_with_month_code() {
        assertEquals(6, num("new Temporal.ZonedDateTime(0n, 'UTC').with({monthCode: 'M06'}).month"));
    }

    @Test
    public void test_rounding_increment_option() {
        assertEquals("1970-01-01T00:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(0n, 'UTC').round({smallestUnit: 'hour', "
                        + "roundingIncrement: 2}).toString()"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.ZonedDateTime(0n, 'UTC').round({smallestUnit: 'hour', roundingIncrement: 0})"));
    }

    @Test
    public void test_rounding_mode_expand() {
        assertEquals("1970-01-01T01:00:00+00:00[UTC]",
                str("new Temporal.ZonedDateTime(1000000000n, 'UTC').round({smallestUnit: 'hour', "
                        + "roundingMode: 'expand'}).toString()"));
    }

    @Test
    public void test_rounding_mode_half_even_at_an_exact_tie() {
        assertEquals("1000", str("new Temporal.ZonedDateTime(1250n, 'UTC').round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 500, roundingMode: 'halfEven'}).epochNanoseconds.toString()"));
    }

    @Test
    public void test_rounding_mode_half_ceil_half_floor_negative_ties() {
        assertEquals("-1000", str("new Temporal.ZonedDateTime(-1250n, 'UTC').round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 500, roundingMode: 'halfCeil'}).epochNanoseconds.toString()"));
        assertEquals("1000", str("new Temporal.ZonedDateTime(1250n, 'UTC').round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 500, roundingMode: 'halfFloor'}).epochNanoseconds.toString()"));
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
    public void test_offset_with_fractional_part_is_trimmed() {
        assertEquals(0, num("Temporal.ZonedDateTime.from('1970-01-01T00:00:00+00:00:00.5[+00:00]').offsetNanoseconds"));
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
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(21600000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'halfTrunc'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(21600000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'trunc'}).toString()"));
        assertEquals("1970-01-02T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(21600000000000n, 'UTC').round("
                + "{smallestUnit: 'day', roundingMode: 'expand'}).toString()"));
    }

    @Test
    public void test_round_signed_nanoseconds_negative_epoch() {
        // round() rounds the receiver's LOCAL wall-clock time, so trunc/expand behave as floor/ceil
        // unconditionally - see round/negative-time.js and round/rounding-direction.js in test262.
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
        assertEquals("1969-12-31T23:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(" + halfHourNs
                + ", 'UTC').round(" + "{smallestUnit: 'hour', roundingMode: 'trunc'}).toString()"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(" + halfHourNs
                + ", 'UTC').round(" + "{smallestUnit: 'hour', roundingMode: 'expand'}).toString()"));
    }

    @Test
    public void test_with_offset_field_z_is_accepted() {
        final var instance = "new Temporal.ZonedDateTime(0n, 'UTC')";
        assertEquals("1970-01-01T05:00:00+00:00[UTC]",
                str(instance + ".with({hour: 5, offset: 'Z'}, {offset: 'use'}).toString()"));
    }

    @Test
    public void test_with_offset_field_wrong_type_throws() {
        final var instance = "new Temporal.ZonedDateTime(0n, 'UTC')";
        assertThrows(TypeErrorException.class, () -> Interpreter.run(instance + ".with({offset: null})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run(instance + ".with({offset: true})"));
    }

    @Test
    public void test_with_calendar_accepts_flexible_string_and_temporal_object() {
        final var instance = "new Temporal.ZonedDateTime(0n, 'UTC')";
        assertEquals("iso8601", str(instance + ".withCalendar('2020-01-01[u-ca=iso8601]').calendarId"));
        assertEquals("iso8601", str(instance + ".withCalendar(new Temporal.PlainDate(2020, 1, 1)).calendarId"));
    }

    @Test
    public void test_with_requires_at_least_one_recognized_property() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').with({})"));
    }

    @Test
    public void test_with_rejects_calendar_and_time_zone_fields() {
        final var instance = "new Temporal.ZonedDateTime(0n, 'UTC')";
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run(instance + ".with({day: 5, calendar: 'iso8601'})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run(instance + ".with({day: 5, timeZone: 'UTC'})"));
    }

    @Test
    public void test_since_with_equal_instants_and_day_unit_does_not_throw() {
        assertEquals(0.0, num("new Temporal.ZonedDateTime(0n, 'UTC').since("
                + "new Temporal.ZonedDateTime(0n, 'UTC'), {smallestUnit: 'day'}).days"));
    }

    @Test
    public void test_round_half_even_majority_remainder() {
        assertEquals("1970-01-01T01:00:00+00:00[UTC]", str("new Temporal.ZonedDateTime(2400000000000n, 'UTC').round("
                + "{smallestUnit: 'hour', roundingMode: 'halfEven'}).toString()"));
    }

    @Test
    public void test_month_and_month_code_must_agree_in_with() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').with({month: 5, monthCode: 'M06'})"));
    }

    @Test
    public void test_with_calendar_time_string_and_leap_second() {
        final var instance = "new Temporal.ZonedDateTime(0n, 'UTC')";
        assertEquals("iso8601", str(instance + ".withCalendar('15:23').calendarId"));
        assertEquals("iso8601", str(instance + ".withCalendar('2016-12-31T23:59:60').calendarId"));
    }
}
