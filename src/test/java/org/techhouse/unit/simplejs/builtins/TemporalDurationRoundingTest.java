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

    @Test
    public void test_round_requires_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).round()"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).round({})"));
    }

    @Test
    public void test_round_rejects_smallest_larger_than_largest() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Duration(0, 0, 0, 0, 1).round({smallestUnit: 'years', largestUnit: 'hours'})"));
    }

    @Test
    public void test_round_rejects_increment_out_of_range() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Duration(0, 0, 0, 0, 1).round({smallestUnit: 'hours', roundingIncrement: 0})"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run(
                "new Temporal.Duration(0, 0, 0, 0, 1).round({smallestUnit: 'hours', roundingIncrement: 2000000000})"));
    }

    @Test
    public void test_relative_to_rejects_invalid_type() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({smallestUnit: 'months', relativeTo: 42})"));
    }

    @Test
    public void test_relative_to_fields_calendar_non_string_is_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, calendar: 5}})"));
    }

    @Test
    public void test_relative_to_fields_calendar_full_iso_string() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364).round({smallestUnit: 'years', relativeTo: "
                + "{year: 2020, month: 1, day: 1, calendar: '2020-06-15[u-ca=iso8601]'}}).years"));
    }

    @Test
    public void test_relative_to_fields_time_zone_non_string_is_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 5}})"));
    }

    @Test
    public void test_relative_to_fields_invalid_month_code() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, monthCode: 'X01', day: 1}})"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, monthCode: 'M13', day: 1}})"));
    }

    @Test
    public void test_round_to_unit() {
        assertEquals("PT1H", str("new Temporal.Duration(0, 0, 0, 0, 1, 20).round({smallestUnit: 'hours'}).toString()"));
        assertEquals("PT2H",
                str("new Temporal.Duration(0, 0, 0, 0, 1, 5).round({smallestUnit: 'hours', roundingMode: 'ceil'})"
                        + ".toString()"));
    }

    @Test
    public void test_round_string_shorthand() {
        assertEquals("PT2H", str("new Temporal.Duration(0, 0, 0, 0, 1, 30).round('hours').toString()"));
    }

    @Test
    public void test_round_half_ceil_and_half_floor() {
        assertEquals("PT1S", str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 500)"
                + ".round({smallestUnit: 'seconds', roundingMode: 'halfCeil'}).toString()"));
        assertEquals("PT0S", str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 500)"
                + ".round({smallestUnit: 'seconds', roundingMode: 'halfFloor'}).toString()"));
    }

    @Test
    public void test_total() {
        assertEquals(1.5, num("new Temporal.Duration(0, 0, 0, 0, 36).total({unit: 'days'})"));
        assertEquals(90, num("new Temporal.Duration(0, 0, 0, 0, 1, 30).total('minutes')"));
    }

    @Test
    public void test_total_requires_unit() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0,0,0,1).total({})"));
    }

    @Test
    public void test_total_calendar_dependent_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).total({unit: 'days'})"));
    }

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

    @Test
    public void test_relative_to_fields_offset_mismatch_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({"
                        + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC', "
                        + "offset: '+05:00'}})"));
    }

    @Test
    public void test_relative_to_fields_offset_invalid_format_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({"
                        + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC', "
                        + "offset: '+00:0000'}})"));
    }

    @Test
    public void test_relative_to_fields_leap_second_clamped() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364).round({smallestUnit: 'years', "
                + "relativeTo: {year: 2020, month: 1, day: 1, second: 60}}).years"));
    }

    @Test
    public void test_round_zoned_relative_to_isolates_days_before_sub_day_rounding() {
        assertEquals("3,16",
                str("var d = new Temporal.Duration(0, 0, 0, 3, 12);"
                        + "var zdt = new Temporal.ZonedDateTime(0n, 'UTC');"
                        + "var r = d.round({smallestUnit: 'hours', roundingIncrement: 8, roundingMode: 'halfEven', "
                        + "relativeTo: zdt});" + "r.days + ',' + r.hours"));
    }

    @Test
    public void test_round_zoned_relative_to_next_day_boundary_out_of_range() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration().round({largestUnit: 'days', smallestUnit: 'minutes', "
                        + "relativeTo: new Temporal.ZonedDateTime(8640000000000000000000n, 'UTC')})"));
    }

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

    @Test
    public void test_relative_to_string_offset_matches_zone() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 400).round({smallestUnit: 'years', "
                + "relativeTo: '2020-01-01T00:00+00:00[UTC]'}).years"));
    }

    @Test
    public void test_total_zoned_relative_to_non_day_unit() {
        assertEquals(24, num("new Temporal.Duration(0, 0, 0, 1).total({unit: 'hours', "
                + "relativeTo: {year: 2024, month: 1, day: 1, timeZone: 'UTC'}})"));
    }

    @Test
    public void test_round_zoned_relative_to_negative_duration() {
        assertTrue(JsEval.bool(
                "var d = new Temporal.Duration(0, 0, 0, -3, -12);" + "var zdt = new Temporal.ZonedDateTime(0n, 'UTC');"
                        + "var r = d.round({smallestUnit: 'hours', roundingIncrement: 8, roundingMode: 'halfEven', "
                        + "relativeTo: zdt});" + "r.sign === -1"));
    }

    @Test
    public void test_relative_to_fields_offset_missing_sign_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({"
                        + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC', "
                        + "offset: '00:00'}})"));
    }

    @Test
    public void test_relative_to_fields_offset_without_colons() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 400).round({smallestUnit: 'years', relativeTo: "
                + "{year: 2020, month: 1, day: 1, timeZone: 'UTC', offset: '+0000'}}).years"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(1).round({"
                        + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC', "
                        + "offset: '+000'}})"));
    }

    @Test
    public void test_relative_to_fields_month_code_non_numeric_suffix_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, monthCode: 'MXX', day: 1}})"));
    }

    @Test
    public void test_relative_to_fields_non_finite_field_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).round({"
                + "smallestUnit: 'months', relativeTo: {year: 2020, month: 1, day: 1, hour: NaN}})"));
    }

    @Test
    public void test_round_increment_greater_than_one_rejected_when_balancing_to_different_largest_unit() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 400).round({smallestUnit: 'days', "
                        + "largestUnit: 'years', roundingIncrement: 2, relativeTo: {year: 2020, month: 1, day: 1}})"));
    }

    @Test
    public void test_round_increment_must_evenly_divide_unit_cycle() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 0, 1).round({smallestUnit: 'hours', "
                        + "roundingIncrement: 7, relativeTo: {year: 2020, month: 1, day: 1}})"));
    }
}
