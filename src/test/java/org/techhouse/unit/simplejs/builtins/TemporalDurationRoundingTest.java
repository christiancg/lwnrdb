package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.test.JsEval;

public class TemporalDurationRoundingTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // round requires an options argument, and at least one of smallestUnit/largestUnit
    @Test
    public void test_round_requires_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).round()"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).round({})"));
    }

    // round rejects a smallestUnit coarser than largestUnit
    @Test
    public void test_round_rejects_smallest_larger_than_largest() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Duration(0, 0, 0, 0, 1).round({smallestUnit: 'years', largestUnit: 'hours'})"));
    }

    // round rejects a roundingIncrement outside [1, 1e9]
    @Test
    public void test_round_rejects_increment_out_of_range() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Duration(0, 0, 0, 0, 1).round({smallestUnit: 'hours', roundingIncrement: 0})"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run(
                "new Temporal.Duration(0, 0, 0, 0, 1).round({smallestUnit: 'hours', roundingIncrement: 2000000000})"));
    }

    // relativeTo rejects a value that is neither a Temporal object, an ISO string, nor a
    // fields-like object
    @Test
    public void test_relative_to_rejects_invalid_type() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({smallestUnit: 'months', relativeTo: 42})"));
    }

    // A relativeTo fields object's `calendar` field must be a string when not a Temporal object
    @Test
    public void test_relative_to_fields_calendar_non_string_is_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, calendar: 5}})"));
    }

    // A relativeTo fields object's `calendar` field accepts a full ISO string carrying (or
    // defaulting) a u-ca annotation, not just a bare identifier
    @Test
    public void test_relative_to_fields_calendar_full_iso_string() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364).round({smallestUnit: 'years', relativeTo: "
                + "{year: 2020, month: 1, day: 1, calendar: '2020-06-15[u-ca=iso8601]'}}).years"));
    }

    // A relativeTo fields object's `timeZone` field must be a string
    @Test
    public void test_relative_to_fields_time_zone_non_string_is_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 5}})"));
    }

    // A relativeTo fields object's `monthCode` must match the "M" + two-digit-month shape
    @Test
    public void test_relative_to_fields_invalid_month_code() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, monthCode: 'X01', day: 1}})"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, monthCode: 'M13', day: 1}})"));
    }

    // round to a unit rounds per roundingMode (default halfExpand)
    @Test
    public void test_round_to_unit() {
        assertEquals("PT1H", str("new Temporal.Duration(0, 0, 0, 0, 1, 20).round({smallestUnit: 'hours'}).toString()"));
        assertEquals("PT2H",
                str("new Temporal.Duration(0, 0, 0, 0, 1, 5).round({smallestUnit: 'hours', roundingMode: 'ceil'})"
                        + ".toString()"));
    }

    // round accepts a bare unit string shorthand
    @Test
    public void test_round_string_shorthand() {
        assertEquals("PT2H", str("new Temporal.Duration(0, 0, 0, 0, 1, 30).round('hours').toString()"));
    }

    // round honors the halfCeil/halfFloor rounding modes on an exact tie
    @Test
    public void test_round_half_ceil_and_half_floor() {
        assertEquals("PT1S", str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 500)"
                + ".round({smallestUnit: 'seconds', roundingMode: 'halfCeil'}).toString()"));
        assertEquals("PT0S", str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 500)"
                + ".round({smallestUnit: 'seconds', roundingMode: 'halfFloor'}).toString()"));
    }

    // total requires a unit option and returns an exact (possibly fractional) number
    @Test
    public void test_total() {
        assertEquals(1.5, num("new Temporal.Duration(0, 0, 0, 0, 36).total({unit: 'days'})"));
        assertEquals(90, num("new Temporal.Duration(0, 0, 0, 0, 1, 30).total('minutes')"));
    }

    // total without a unit is a RangeError
    @Test
    public void test_total_requires_unit() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0,0,0,1).total({})"));
    }

    // total on a duration with a year/month/week component is a documented RangeError
    @Test
    public void test_total_calendar_dependent_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).total({unit: 'days'})"));
    }

    // round accepts a largestUnit alone (smallestUnit defaults to nanoseconds, i.e. exact carrying)
    @Test
    public void test_round_with_only_largest_unit() {
        assertEquals("PT25H", str("new Temporal.Duration(0, 0, 0, 1, 1).round({largestUnit: 'hours'}).toString()"));
    }

    @Test
    public void test_round_result_out_of_range_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.Duration.from({seconds: Number.MAX_SAFE_INTEGER, "
                        + "nanoseconds: 999999999}).round({smallestUnit: 'seconds'})"));
    }

    // A relativeTo fields object's `offset` accepts a real string or any object-like value (coerced
    // via ToString), but rejects a bare non-string primitive
    @Test
    public void test_relative_to_fields_offset_must_be_string_or_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC', offset: 5}})"));
    }

    @Test
    public void test_relative_to_fields_offset_object_is_coerced_via_to_string() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364).round({smallestUnit: 'years', relativeTo: "
                + "{year: 2020, month: 1, day: 1, timeZone: 'UTC', offset: {toString(){ return '+00:00'; }}}}).years"));
    }

    // An `offset` that disagrees with the named time zone's actual offset is a RangeError, mirroring
    // an ISO string's own offset/bracket consistency check
    @Test
    public void test_relative_to_fields_offset_mismatch_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({"
                        + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC', "
                        + "offset: '+05:00'}})"));
    }

    // A malformed offset string (inconsistent hour/minute/second separator usage) is rejected by the
    // manual offset parser, not silently misparsed
    @Test
    public void test_relative_to_fields_offset_invalid_format_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({"
                        + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC', "
                        + "offset: '+00:0000'}})"));
    }

    // A leap second (":60") in a relativeTo fields object is clamped to :59, mirroring the ISO-string
    // grammar's own unconditional clamp
    @Test
    public void test_relative_to_fields_leap_second_clamped() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364).round({smallestUnit: 'years', "
                + "relativeTo: {year: 2020, month: 1, day: 1, second: 60}}).years"));
    }

    // A zoned relativeTo's calendar-days portion is resolved separately from its sub-day remainder
    // before rounding (days are not a fixed 86400s under DST) - so an increment spanning across a
    // day boundary rounds differently than it would for a plain (non-zoned) relativeTo
    @Test
    public void test_round_zoned_relative_to_isolates_days_before_sub_day_rounding() {
        assertEquals("3,16",
                str("var d = new Temporal.Duration(0, 0, 0, 3, 12);"
                        + "var zdt = new Temporal.ZonedDateTime(0n, 'UTC');"
                        + "var r = d.round({smallestUnit: 'hours', roundingIncrement: 8, roundingMode: 'halfEven', "
                        + "relativeTo: zdt});" + "r.days + ',' + r.hours"));
    }

    // Rounding with largestUnit "days" against a zoned relativeTo must resolve the next calendar
    // day's boundary even for a zero-length span - a relativeTo sitting at the very edge of the
    // representable range still throws instead of silently answering a blank duration
    @Test
    public void test_round_zoned_relative_to_next_day_boundary_out_of_range() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration().round({largestUnit: 'days', smallestUnit: 'minutes', "
                        + "relativeTo: new Temporal.ZonedDateTime(8640000000000000000000n, 'UTC')})"));
    }

    // total()'s "days" unit against a zoned relativeTo has the same next-day-boundary requirement
    @Test
    public void test_total_zoned_relative_to_next_day_boundary_out_of_range() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run(
                "new Temporal.Duration().total({unit: 'days', " + "relativeTo: '+275760-09-12T00:00:01+00:00[UTC]'})"));
    }

    @Test
    public void test_total_zoned_relative_to_days_normal_case() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 0, 24).total({unit: 'days', "
                + "relativeTo: {year: 2024, month: 1, day: 1, timeZone: 'UTC'}})"));
    }

    // round() accepts an explicit largestUnit together with a relativeTo, and the explicit "auto"
    // string shorthand for largestUnit
    @Test
    public void test_round_relative_to_with_explicit_largest_unit() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 400).round({largestUnit: 'years', smallestUnit: 'days', "
                + "relativeTo: {year: 2020, month: 1, day: 1}}).years"));
    }

    @Test
    public void test_round_largest_unit_auto_string() {
        assertEquals("PT2H",
                str("new Temporal.Duration(0, 0, 0, 0, 1, 30).round({smallestUnit: 'hours', largestUnit: 'auto'})"
                        + ".toString()"));
    }

    // A relativeTo ISO string carrying an explicit offset that agrees with its bracketed zone succeeds
    @Test
    public void test_relative_to_string_offset_matches_zone() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 400).round({smallestUnit: 'years', "
                + "relativeTo: '2020-01-01T00:00+00:00[UTC]'}).years"));
    }

    // total() with a non-"days" unit against a zoned relativeTo skips the day-boundary check entirely
    @Test
    public void test_total_zoned_relative_to_non_day_unit() {
        assertEquals(24, num("new Temporal.Duration(0, 0, 0, 1).total({unit: 'hours', "
                + "relativeTo: {year: 2024, month: 1, day: 1, timeZone: 'UTC'}})"));
    }

    // The zoned sub-day rounding branch handles a negative duration the same way as a positive one
    @Test
    public void test_round_zoned_relative_to_negative_duration() {
        assertTrue(JsEval.bool(
                "var d = new Temporal.Duration(0, 0, 0, -3, -12);" + "var zdt = new Temporal.ZonedDateTime(0n, 'UTC');"
                        + "var r = d.round({smallestUnit: 'hours', roundingIncrement: 8, roundingMode: 'halfEven', "
                        + "relativeTo: zdt});" + "r.sign === -1"));
    }

    // A relativeTo offset with no sign at all is rejected before any digit parsing is attempted
    @Test
    public void test_relative_to_fields_offset_missing_sign_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({"
                        + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC', "
                        + "offset: '00:00'}})"));
    }

    // The manual offset parser also accepts the no-colon "+HHMM"/"+HHMMSS" forms
    @Test
    public void test_relative_to_fields_offset_without_colons() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 400).round({smallestUnit: 'years', relativeTo: "
                + "{year: 2020, month: 1, day: 1, timeZone: 'UTC', offset: '+0000'}}).years"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({"
                        + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC', "
                        + "offset: '+000'}})"));
    }

    // A monthCode with a non-numeric suffix is a RangeError, not an uncaught NumberFormatException
    @Test
    public void test_relative_to_fields_month_code_non_numeric_suffix_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, monthCode: 'MXX', day: 1}})"));
    }

    // A non-finite relativeTo fields-object field (e.g. NaN) is a RangeError
    @Test
    public void test_relative_to_fields_non_finite_field_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, hour: NaN}})"));
    }

    // A roundingIncrement greater than 1 is rejected for a date-or-finer smallestUnit when balancing
    // to a different (coarser) largestUnit
    @Test
    public void test_round_increment_greater_than_one_rejected_when_balancing_to_different_largest_unit() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 400).round({smallestUnit: 'days', "
                        + "largestUnit: 'years', roundingIncrement: 2, relativeTo: {year: 2020, month: 1, day: 1}})"));
    }

    // A roundingIncrement that doesn't evenly divide its unit's natural cycle length is rejected
    // (validateRoundingIncrementForUnit only runs on the relativeTo-anchored path)
    @Test
    public void test_round_increment_must_evenly_divide_unit_cycle() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 0, 1).round({smallestUnit: 'hours', "
                        + "roundingIncrement: 7, relativeTo: {year: 2020, month: 1, day: 1}})"));
    }
}
