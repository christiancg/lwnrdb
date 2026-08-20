package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TemporalPlainTimeBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_default_constructor_fields_are_zero() {
        assertEquals("00:00:00", str("new Temporal.PlainTime().toString()"));
    }

    @Test
    public void test_constructor_with_all_fields() {
        assertEquals("12:30:15.1002", str("new Temporal.PlainTime(12, 30, 15, 100, 200, 0).toString()"));
    }

    @Test
    public void test_constructor_fields_round_trip() {
        assertEquals("01:02:03.004005006", str("new Temporal.PlainTime(1, 2, 3, 4, 5, 6).toString()"));
    }

    @Test
    public void test_field_getters() {
        final var setup = "let t = new Temporal.PlainTime(1, 2, 3, 4, 5, 6); ";
        assertEquals(1, num(setup + "t.hour"));
        assertEquals(2, num(setup + "t.minute"));
        assertEquals(3, num(setup + "t.second"));
        assertEquals(4, num(setup + "t.millisecond"));
        assertEquals(5, num(setup + "t.microsecond"));
        assertEquals(6, num(setup + "t.nanosecond"));
    }

    @Test
    public void test_typeof_is_object() {
        assertEquals("object", str("typeof new Temporal.PlainTime()"));
    }

    @Test
    public void test_to_string_tag() {
        assertEquals("[object Temporal.PlainTime]", str("Object.prototype.toString.call(new Temporal.PlainTime())"));
    }

    @Test
    public void test_constructor_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainTime(1, 2, 3)"));
    }

    @Test
    public void test_constructor_rejects_out_of_range_hour() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(24)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(-1)"));
    }

    @Test
    public void test_constructor_rejects_out_of_range_minute_and_second() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 60)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 60)"));
    }

    @Test
    public void test_constructor_rejects_out_of_range_subsecond_fields() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 0, 1000)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 0, 0, 1000)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 0, 0, 0, 1000)"));
    }

    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("+new Temporal.PlainTime(1)"));
    }

    @Test
    public void test_to_json_and_to_locale_string_match_to_string() {
        assertTrue(bool("var t = new Temporal.PlainTime(9, 5, 0); "
                + "t.toJSON() === t.toString() && t.toLocaleString() === t.toString()"));
    }

    @Test
    public void test_equals() {
        assertTrue(bool("new Temporal.PlainTime(1, 2, 3).equals(new Temporal.PlainTime(1, 2, 3))"));
        assertFalse(bool("new Temporal.PlainTime(1, 2, 3).equals(new Temporal.PlainTime(1, 2, 4))"));
        assertTrue(bool("new Temporal.PlainTime(1, 2, 3).equals('01:02:03')"));
    }

    @Test
    public void test_static_compare() {
        assertEquals(-1, num("Temporal.PlainTime.compare(new Temporal.PlainTime(1), new Temporal.PlainTime(2))"));
        assertEquals(0, num("Temporal.PlainTime.compare(new Temporal.PlainTime(1), new Temporal.PlainTime(1))"));
        assertEquals(1, num("Temporal.PlainTime.compare(new Temporal.PlainTime(2), new Temporal.PlainTime(1))"));
    }

    @Test
    public void test_from_string() {
        assertEquals("12:30:00", str("Temporal.PlainTime.from('12:30:00').toString()"));
    }

    @Test
    public void test_from_plain_time_creates_a_new_instance() {
        assertTrue(bool("var a = new Temporal.PlainTime(1, 2, 3); var b = Temporal.PlainTime.from(a); "
                + "a !== b && a.equals(b)"));
    }

    // ToTemporalTime's fast paths for PlainDateTime/ZonedDateTime arguments bypass the generic
    // property-bag path entirely
    @Test
    public void test_from_plain_date_time_and_zoned_date_time_fast_paths() {
        assertEquals("10:30:00",
                str("Temporal.PlainTime.from(new Temporal.PlainDateTime(2020, 6, 15, 10, 30)).toString()"));
        assertEquals("10:00:00", str("Temporal.PlainTime.from("
                + "Temporal.ZonedDateTime.from('2020-06-15T10:00:00-04:00[America/New_York]')).toString()"));
    }

    @Test
    public void test_from_object_default_overflow_constrains() {
        assertEquals(23, num("Temporal.PlainTime.from({hour: 25}).hour"));
    }

    @Test
    public void test_from_object_reject_overflow_throws() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainTime.from({hour: 25}, {overflow: 'reject'})"));
    }

    @Test
    public void test_with_overrides_given_fields() {
        assertEquals("05:02:03", str("new Temporal.PlainTime(1, 2, 3).with({hour: 5}).toString()"));
    }

    @Test
    public void test_with_rejects_a_real_plain_time() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).with(new Temporal.PlainTime(2))"));
    }

    @Test
    public void test_with_rejects_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).with({})"));
    }

    @Test
    public void test_add_wraps_around_24_hours() {
        assertEquals("01:00:00", str("new Temporal.PlainTime(23, 0, 0).add({hours: 2}).toString()"));
    }

    @Test
    public void test_subtract_wraps_backward() {
        assertEquals("23:00:00", str("new Temporal.PlainTime(1, 0, 0).subtract({hours: 2}).toString()"));
    }

    @Test
    public void test_add_rejects_mixed_sign_duration() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).add({hours: 1, minutes: -1})"));
    }

    @Test
    public void test_round_to_minute() {
        assertEquals("12:35:00", str("new Temporal.PlainTime(12, 34, 56).round({smallestUnit: 'minute'}).toString()"));
    }

    @Test
    public void test_round_accepts_a_string_shorthand() {
        assertEquals("13:00:00", str("new Temporal.PlainTime(12, 34, 56).round('hour').toString()"));
    }

    @Test
    public void test_round_requires_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).round()"));
    }

    @Test
    public void test_get_iso_fields() {
        assertTrue(bool("var f = new Temporal.PlainTime(1, 2, 3, 4, 5, 6).getISOFields(); "
                + "f.isoHour === 1 && f.isoMinute === 2 && f.isoSecond === 3 && f.isoMillisecond === 4"
                + " && f.isoMicrosecond === 5 && f.isoNanosecond === 6"));
    }

    @Test
    public void test_until_and_since() {
        assertTrue(bool("var d = new Temporal.PlainTime(10, 0, 0).until(new Temporal.PlainTime(12, 30, 0)); "
                + "d.hours === 2 && d.minutes === 30"));
        assertTrue(bool("var d = new Temporal.PlainTime(12, 30, 0).since(new Temporal.PlainTime(10, 0, 0)); "
                + "d.hours === 2 && d.minutes === 30"));
    }

    @Test
    public void test_brand_check_on_a_foreign_receiver() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.prototype.toString.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Object.getOwnPropertyDescriptor(Temporal.PlainTime.prototype, 'hour')" + ".get.call({})"));
    }

    @Test
    public void test_unknown_member_is_undefined() {
        assertEquals("undefined", str("typeof new Temporal.PlainTime().nope"));
    }

    // round() sweeps every rounding mode, positive side (via non-negative-only roundNonNegative)
    @Test
    public void test_round_all_modes() {
        final var setup = "new Temporal.PlainTime(0, 0, 0, 0, 0, %d)"
                + ".round({smallestUnit: 'nanosecond', roundingIncrement: 10, roundingMode: '%s'}).nanosecond";
        assertEquals(20, num(String.format(setup, 13, "ceil")));
        assertEquals(10, num(String.format(setup, 13, "floor")));
        assertEquals(10, num(String.format(setup, 13, "trunc")));
        assertEquals(20, num(String.format(setup, 15, "halfExpand")));
        assertEquals(20, num(String.format(setup, 15, "halfCeil")));
        assertEquals(10, num(String.format(setup, 15, "halfFloor")));
        assertEquals(10, num(String.format(setup, 15, "halfTrunc")));
        assertEquals(20, num(String.format(setup, 15, "halfEven")));
        assertEquals(20, num(String.format(setup, 25, "halfEven")));
    }

    // round() carries over the day boundary (mod 86400e9) when rounding up past midnight
    @Test
    public void test_round_wraps_past_midnight() {
        assertEquals("00:00:00", str("new Temporal.PlainTime(23, 59, 59, 999, 999, 999)"
                + ".round({smallestUnit: 'second', roundingMode: 'ceil'}).toString()"));
    }

    // round() accepts every fixed time unit through hour
    @Test
    public void test_round_every_unit() {
        assertEquals(0, num("new Temporal.PlainTime(0, 0, 0, 0, 0, 1)"
                + ".round({smallestUnit: 'hour', roundingIncrement: 12, roundingMode: 'floor'}).hour"));
        assertEquals(0, num("new Temporal.PlainTime(0, 0, 0, 0, 0, 1)"
                + ".round({smallestUnit: 'minute', roundingIncrement: 30, roundingMode: 'floor'}).minute"));
        assertEquals(0, num("new Temporal.PlainTime(0, 0, 0, 0, 0, 1)"
                + ".round({smallestUnit: 'second', roundingIncrement: 30, roundingMode: 'floor'}).second"));
        assertEquals(0,
                num("new Temporal.PlainTime(0, 0, 0, 0, 0, 1)"
                        + ".round({smallestUnit: 'millisecond', roundingIncrement: 500, roundingMode: 'floor'})"
                        + ".millisecond"));
        assertEquals(0,
                num("new Temporal.PlainTime(0, 0, 0, 0, 0, 1)"
                        + ".round({smallestUnit: 'microsecond', roundingIncrement: 500, roundingMode: 'floor'})"
                        + ".microsecond"));
    }

    // round() rejects a day-or-larger smallestUnit
    @Test
    public void test_round_rejects_day_unit() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).round({smallestUnit: 'day'})"));
    }

    // roundingIncrement must be a positive integer, evenly divide the unit maximum, and not equal it
    @Test
    public void test_round_invalid_increment() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).round({smallestUnit: 'hour', roundingIncrement: 5})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainTime(1).round({smallestUnit: 'hour', roundingIncrement: 24})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainTime(1).round({smallestUnit: 'second', roundingIncrement: 0})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainTime(1).round({smallestUnit: 'second', roundingIncrement: 1_000_000_001})"));
    }

    // toString() honours fractionalSecondDigits (numeric and "auto"), smallestUnit and roundingMode
    @Test
    public void test_to_string_fractional_second_digits() {
        assertEquals("00:00:00.500000000",
                str("new Temporal.PlainTime(0, 0, 0, 500).toString({fractionalSecondDigits: 9})"));
        assertEquals("00:00:00", str("new Temporal.PlainTime(0, 0, 0, 500).toString({fractionalSecondDigits: 0})"));
        assertEquals("00:00:00.5",
                str("new Temporal.PlainTime(0, 0, 0, 500).toString({fractionalSecondDigits: 'auto'})"));
    }

    @Test
    public void test_to_string_fractional_second_digits_invalid() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toString({fractionalSecondDigits: -1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toString({fractionalSecondDigits: 10})"));
    }

    @Test
    public void test_to_string_smallest_unit_minute() {
        assertEquals("12:34", str("new Temporal.PlainTime(12, 34, 56).toString({smallestUnit: 'minute'})"));
    }

    @Test
    public void test_to_string_smallest_unit_other() {
        assertEquals("12:34:57", str(
                "new Temporal.PlainTime(12, 34, 56, 500).toString({smallestUnit: 'second', roundingMode: 'ceil'})"));
        assertEquals("12:34:56.500",
                str("new Temporal.PlainTime(12, 34, 56, 500).toString({smallestUnit: 'millisecond'})"));
    }

    @Test
    public void test_to_string_rejects_day_smallest_unit() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toString({smallestUnit: 'hour'})"));
    }

    @Test
    public void test_to_string_requires_object_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).toString(5)"));
    }

    // until()/since() honour largestUnit/smallestUnit/roundingIncrement/roundingMode options
    @Test
    public void test_until_with_options() {
        assertTrue(bool("var d = new Temporal.PlainTime(10, 0, 0).until(new Temporal.PlainTime(11, 30, 0), "
                + "{largestUnit: 'minute'}); d.minutes === 90"));
        assertEquals(30, num("new Temporal.PlainTime(10, 0, 0).until(new Temporal.PlainTime(10, 0, 20), "
                + "{smallestUnit: 'second', roundingIncrement: 30, roundingMode: 'halfExpand'}).seconds"));
    }

    // since() negates the rounding mode relative to until() (ceil/floor swap, halfCeil/halfFloor swap)
    @Test
    public void test_since_negates_rounding_mode_ceil_floor() {
        assertEquals(1, num("new Temporal.PlainTime(10, 0, 20).since(new Temporal.PlainTime(10, 0, 0), "
                + "{smallestUnit: 'minute', roundingMode: 'ceil'}).minutes"));
        assertEquals(0, num("new Temporal.PlainTime(10, 0, 20).since(new Temporal.PlainTime(10, 0, 0), "
                + "{smallestUnit: 'minute', roundingMode: 'floor'}).minutes"), 0.0);
    }

    @Test
    public void test_since_negates_rounding_mode_half_ceil_floor() {
        assertEquals(1, num("new Temporal.PlainTime(10, 0, 30).since(new Temporal.PlainTime(10, 0, 0), "
                + "{smallestUnit: 'minute', roundingMode: 'halfCeil'}).minutes"));
        assertEquals(0, num("new Temporal.PlainTime(10, 0, 30).since(new Temporal.PlainTime(10, 0, 0), "
                + "{smallestUnit: 'minute', roundingMode: 'halfFloor'}).minutes"), 0.0);
    }

    // until()/since() reject a largestUnit/smallestUnit larger than hour
    @Test
    public void test_until_rejects_day_units() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainTime(1).until(new Temporal.PlainTime(2), {largestUnit: 'day'})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainTime(1).until(new Temporal.PlainTime(2), {smallestUnit: 'day'})"));
    }

    @Test
    public void test_until_rejects_smallest_larger_than_largest() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).until(new Temporal.PlainTime(2), "
                        + "{smallestUnit: 'hour', largestUnit: 'second'})"));
    }

    @Test
    public void test_until_options_must_be_object() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).until(new Temporal.PlainTime(2), 5)"));
    }

    @Test
    public void test_until_largest_unit_auto_defaults_to_hour() {
        assertEquals(2, num("new Temporal.PlainTime(10, 0, 0).until(new Temporal.PlainTime(12, 30, 0), "
                + "{largestUnit: 'auto'}).hours"));
    }

    // readOverflowOption rejects an invalid overflow value and a non-object options argument
    @Test
    public void test_overflow_option_invalid() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainTime.from({hour: 1}, {overflow: 'bogus'})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.from({hour: 1}, 5)"));
    }

    // from() rejects a time-like object with no recognized fields
    @Test
    public void test_from_rejects_object_with_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.from({})"));
    }

    // Reflect.construct(Temporal.PlainTime, args, newTarget) links the new instance's prototype to
    // newTarget.prototype instead of the intrinsic Temporal.PlainTime prototype
    @Test
    public void test_reflect_construct_links_new_target_prototype() {
        assertTrue(bool("""
                var Ctor = function() {};
                var instance = Reflect.construct(Temporal.PlainTime, [1, 2, 3], Ctor);
                Object.getPrototypeOf(instance) === Ctor.prototype
                    && Object.getOwnPropertyDescriptor(Temporal.PlainTime.prototype, "hour").get.call(instance) === 1
                """));
    }

    @Test
    public void test_plain_new_keeps_plain_time_prototype() {
        assertTrue(bool("Object.getPrototypeOf(new Temporal.PlainTime()) === Temporal.PlainTime.prototype"));
    }

    // toPlainDateTime requires a date-like object, and requires each of year/month/day
    @Test
    public void test_to_plain_date_time_requires_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).toPlainDateTime(5)"));
    }

    @Test
    public void test_to_plain_date_time_requires_year_month_day() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toPlainDateTime({month: 1, day: 1})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toPlainDateTime({year: 2024, day: 1})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toPlainDateTime({year: 2024, month: 1})"));
    }

    // numberField (used by add/subtract) rejects a non-integer/NaN/infinite duration field
    @Test
    public void test_add_rejects_invalid_duration_fields() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).add({hours: 1.5})"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).add({hours: NaN})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).add({hours: Infinity})"));
    }

    @Test
    public void test_add_requires_object_duration() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).add(5)"));
    }

    // add()/subtract() now also accept a real Temporal.Duration instance or an ISO 8601 duration
    // string, not just a plain duration-like object
    @Test
    public void test_add_accepts_duration_instance_and_string() {
        assertEquals("03:00:00", str("new Temporal.PlainTime(1).add(Temporal.Duration.from({hours: 2})).toString()"));
        assertEquals("03:00:00", str("new Temporal.PlainTime(1).add('PT2H').toString()"));
        assertEquals("23:00:00", str("new Temporal.PlainTime(1).subtract('PT2H').toString()"));
    }

    // A date-unit carry (days and above) from the duration argument is silently discarded, not
    // rejected, since PlainTime wraps around 24 hours with no date component
    @Test
    public void test_add_discards_date_unit_carry() {
        assertEquals("01:00:00", str("new Temporal.PlainTime(1).add({days: 5}).toString()"));
    }

    // A duration-like object with none of the ten recognized properties present is a TypeError
    @Test
    public void test_add_rejects_duration_like_with_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).add({})"));
    }
}
