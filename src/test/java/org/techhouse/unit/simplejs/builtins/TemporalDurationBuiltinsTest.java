package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TemporalDurationBuiltinsTest {
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
    public void test_defaults() {
        assertEquals("object", str("typeof new Temporal.Duration()"));
        assertEquals("PT0S", str("new Temporal.Duration().toString()"));
    }

    @Test
    public void test_constructor_fields() {
        assertEquals("P1Y2M3W4DT5H6M7.00800901S",
                str("new Temporal.Duration(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).toString()"));
    }

    @Test
    public void test_mixed_sign_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1, -1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, -1, 1)"));
    }

    @Test
    public void test_non_integer_field_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1.5)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(NaN)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(Infinity)"));
    }

    @Test
    public void test_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Duration()"));
    }

    @Test
    public void test_field_getters() {
        assertEquals("1,2,3,4,5,6,7,8,9,10",
                str("var d = new Temporal.Duration(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);"
                        + "[d.years, d.months, d.weeks, d.days, d.hours, d.minutes, d.seconds, d.milliseconds,"
                        + " d.microseconds, d.nanoseconds].join(',')"));
        assertEquals(1, num("new Temporal.Duration(1).sign"));
        assertEquals(-1, num("new Temporal.Duration(-1).sign"));
        assertEquals(0, num("new Temporal.Duration().sign"));
        assertTrue(bool("new Temporal.Duration().blank"));
        assertTrue(bool("!new Temporal.Duration(1).blank"));
    }

    @Test
    public void test_negated() {
        assertEquals("-P1DT2H", str("new Temporal.Duration(0, 0, 0, 1, 2).negated().toString()"));
        assertEquals("P1DT2H", str("new Temporal.Duration(0, 0, 0, -1, -2).negated().toString()"));
    }

    @Test
    public void test_abs() {
        assertEquals("P1DT2H", str("new Temporal.Duration(0, 0, 0, -1, -2).abs().toString()"));
    }

    @Test
    public void test_with() {
        assertEquals("P1DT5H", str("new Temporal.Duration(0, 0, 0, 1, 2).with({hours: 5}).toString()"));
    }

    @Test
    public void test_with_requires_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration().with(1)"));
    }

    @Test
    public void test_with_rejects_empty_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).with({})"));
    }

    @Test
    public void test_add_rejects_non_duration_argument() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).add(42)"));
    }

    @Test
    public void test_unrecognized_member_is_undefined() {
        assertTrue(bool("typeof new Temporal.Duration(0, 0, 0, 1).notAMethod === 'undefined'"));
    }

    @Test
    public void test_add_subtract() {
        assertEquals("P1DT1H", str("new Temporal.Duration(0, 0, 0, 1).add({hours: 1}).toString()"));
        assertEquals("PT23H", str("new Temporal.Duration(0, 0, 0, 1).subtract({hours: 1}).toString()"));
    }

    @Test
    public void test_add_calendar_dependent_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).add({days: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).add({years: 1})"));
    }

    @Test
    public void test_subtract_to_zero() {
        assertEquals("PT0S", str("new Temporal.Duration(0, 0, 0, 1).subtract({hours: 24}).toString()"));
        assertTrue(bool("new Temporal.Duration(0, 0, 0, 1).subtract({hours: 24}).blank"));
    }

    @Test
    public void test_to_json_and_locale_string() {
        assertEquals("PT1H", str("new Temporal.Duration(0, 0, 0, 0, 1).toJSON()"));
        assertEquals("PT1H", str("new Temporal.Duration(0, 0, 0, 0, 1).toLocaleString()"));
        assertEquals("\"PT1H\"", str("JSON.stringify(new Temporal.Duration(0, 0, 0, 0, 1))"));
    }

    @Test
    public void test_to_string_fractional_digits() {
        assertEquals("PT1.500S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500).toString({fractionalSecondDigits: 3})"));
        assertEquals("PT0.000S", str("new Temporal.Duration().toString({fractionalSecondDigits: 3})"));
    }

    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration().valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1) + 1"));
    }

    @Test
    public void test_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Duration.prototype.toString.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Object.getOwnPropertyDescriptor(" + "Temporal.Duration.prototype, 'years').get.call({})"));
    }

    @Test
    public void test_from() {
        assertEquals("P1D", str("Temporal.Duration.from('P1D').toString()"));
        assertEquals("P1D", str("Temporal.Duration.from({days: 1}).toString()"));
        assertEquals("P1D", str("Temporal.Duration.from(new Temporal.Duration(0,0,0,1)).toString()"));
    }

    @Test
    public void test_from_rejects_invalid_input() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Duration.from({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Duration.from(1)"));
    }

    @Test
    public void test_from_string_rejects_excess_fraction_digits() {
        // TemporalDecimalFraction is bounded to 1-9 digits; a 10th digit is a RangeError, not a
        // value to silently truncate, per test262.
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.Duration.from('PT1.123456789999S')"));
    }

    @Test
    public void test_from_string_malformed_seconds_component() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.Duration.from('PT1.5')"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.Duration.from('PT1.S')"));
    }

    @Test
    public void test_compare() {
        assertEquals(-1, num("Temporal.Duration.compare({hours: 1}, {hours: 2})"));
        assertEquals(1, num("Temporal.Duration.compare({hours: 2}, {hours: 1})"));
        assertEquals(0, num("Temporal.Duration.compare({minutes: 60}, {hours: 1})"));
    }

    @Test
    public void test_compare_accepts_a_string_operand() {
        assertEquals(0, num("Temporal.Duration.compare('PT1H', {minutes: 60})"));
        assertEquals(0, num("Temporal.Duration.compare(new Temporal.Duration(0, 0, 0, 0, 2), 'PT2H')"));
    }

    @Test
    public void test_compare_calendar_dependent_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.Duration.compare({years: 1, hours: 1}, {years: 1, hours: 2})"));
    }

    @Test
    public void test_compare_identical_operands_with_calendar_fields() {
        assertEquals(0, num("Temporal.Duration.compare(new Temporal.Duration(5, 5, 5, 5, 5, 5, 5, 5, 5, 5),"
                + " new Temporal.Duration(5, 5, 5, 5, 5, 5, 5, 5, 5, 5))"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter
                        .run("Temporal.Duration.compare(" + "new Temporal.Duration(5, 5, 5, 5, 5, 5, 5, 5, 5, 5),"
                                + " new Temporal.Duration(5, 5, 5, 5, 4, 65, 5, 5, 5, 5))"));
    }

    @Test
    public void test_add_accepts_a_string_operand() {
        assertEquals("P1DT1H", str("new Temporal.Duration(0, 0, 0, 1).add('PT1H').toString()"));
    }

    @Test
    public void test_explicit_undefined_argument() {
        assertEquals("P1Y3D", str("new Temporal.Duration(1, undefined, 0, 3).toString()"));
    }

    @Test
    public void test_options_must_be_object_or_string() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0,0,0,1).round(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0,0,0,1).total(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0,0,0,1).toString(5)"));
    }

    @Test
    public void test_to_string_smallest_unit() {
        assertEquals("PT1.500S", str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500)"
                + ".toString({smallestUnit: 'milliseconds'}).toString()"));
        assertEquals("PT1.500000S", str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500)"
                + ".toString({smallestUnit: 'microseconds'}).toString()"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0,0,0,1).toString({smallestUnit: 'hours'})"));
    }

    @Test
    public void test_to_string_fractional_digits_auto() {
        assertEquals("PT1.5S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500).toString({fractionalSecondDigits: 'auto'})"));
    }

    @Test
    public void test_to_string_tag() {
        assertEquals("[object Temporal.Duration]", str("Object.prototype.toString.call(new Temporal.Duration())"));
    }

    @Test
    public void test_subclass() {
        assertEquals("P1D", str("class D extends Temporal.Duration {}" + "new D(0, 0, 0, 1).toString()"));
    }

    @Test
    public void test_out_of_range_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(4294967296)"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 9007199254740992)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(Number.MAX_VALUE)"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 0, 0, Number.MAX_VALUE)"));
    }

    @Test
    public void test_from_string_infinite_component_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.Duration.from('P' + '9'.repeat(400) + 'Y')"));
    }

    @Test
    public void test_add_balances_to_receivers_own_finest_unit() {
        assertEquals("PT0.000002S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 0, 1).add({microseconds: 1})" + ".toString()"));
        assertEquals("PT1.000001S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).add({microseconds: 1}).toString()"));
        assertEquals("PT0.001S", str("new Temporal.Duration().add({milliseconds: 1}).toString()"));
    }

    @Test
    public void test_with_result_out_of_range_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).with({seconds: 9007199254740992})"));
    }

    @Test
    public void test_to_string_smallest_unit_result_out_of_range_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.Duration.from({seconds: Number.MAX_SAFE_INTEGER, milliseconds: 999})"
                        + ".toString({smallestUnit: 'seconds', roundingMode: 'ceil'})"));
    }

    @Test
    public void test_reflect_construct_propagates_new_target_prototype_getter_throw() {
        assertEquals("boom", str("var newTarget = Object.defineProperty(function(){}.bind(), 'prototype', "
                + "{get(){ throw 'boom'; }});" + "var caught;"
                + "try { Reflect.construct(Temporal.Duration, [], newTarget); } catch (e) { caught = e; }" + "caught"));
    }

    @Test
    public void test_reflect_construct_links_to_new_target_prototype() {
        assertTrue(bool("var proto = {marker: true};" + "var newTarget = function(){}; newTarget.prototype = proto;"
                + "var instance = Reflect.construct(Temporal.Duration, [0, 0, 0, 1], newTarget);"
                + "Object.getPrototypeOf(instance) === proto && instance.days === 1"));
    }

    @Test
    public void test_months_and_weeks_out_of_range_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 4294967296)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 4294967296)"));
    }

    @Test
    public void test_add_default_largest_unit_hours_and_minutes() {
        assertEquals("PT2H", str("new Temporal.Duration(0, 0, 0, 0, 1).add({hours: 1}).toString()"));
        assertEquals("PT2M", str("new Temporal.Duration(0, 0, 0, 0, 0, 1).add({minutes: 1}).toString()"));
    }

    @Test
    public void test_to_string_fractional_digits_invalid_value_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).toString({" + "fractionalSecondDigits: 'bogus'})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).toString({" + "fractionalSecondDigits: 15})"));
    }
}
