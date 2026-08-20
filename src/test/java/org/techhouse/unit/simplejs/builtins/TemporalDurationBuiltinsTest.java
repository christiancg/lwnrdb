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

    // Defaults are all zero, and typeof is object
    @Test
    public void test_defaults() {
        assertEquals("object", str("typeof new Temporal.Duration()"));
        assertEquals("PT0S", str("new Temporal.Duration().toString()"));
    }

    // Every constructor argument lands in the matching field
    @Test
    public void test_constructor_fields() {
        assertEquals("P1Y2M3W4DT5H6M7.00800901S",
                str("new Temporal.Duration(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).toString()"));
    }

    // Mixed-sign fields are rejected
    @Test
    public void test_mixed_sign_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1, -1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, -1, 1)"));
    }

    // A non-integer field is a RangeError
    @Test
    public void test_non_integer_field_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1.5)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(NaN)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(Infinity)"));
    }

    // Temporal.Duration is not callable as a plain function
    @Test
    public void test_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Duration()"));
    }

    // The 10 field getters plus sign/blank
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

    // negated flips every field's sign
    @Test
    public void test_negated() {
        assertEquals("-P1DT2H", str("new Temporal.Duration(0, 0, 0, 1, 2).negated().toString()"));
        assertEquals("P1DT2H", str("new Temporal.Duration(0, 0, 0, -1, -2).negated().toString()"));
    }

    // abs makes every field positive
    @Test
    public void test_abs() {
        assertEquals("P1DT2H", str("new Temporal.Duration(0, 0, 0, -1, -2).abs().toString()"));
    }

    // with overrides only the given fields
    @Test
    public void test_with() {
        assertEquals("P1DT5H", str("new Temporal.Duration(0, 0, 0, 1, 2).with({hours: 5}).toString()"));
    }

    // with requires an object
    @Test
    public void test_with_requires_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration().with(1)"));
    }

    // add/subtract combine calendar-independent fields
    @Test
    public void test_add_subtract() {
        assertEquals("P1DT1H", str("new Temporal.Duration(0, 0, 0, 1).add({hours: 1}).toString()"));
        assertEquals("PT23H", str("new Temporal.Duration(0, 0, 0, 1).subtract({hours: 1}).toString()"));
    }

    // add/subtract with a year/month/week component is a documented RangeError, not a crash
    @Test
    public void test_add_calendar_dependent_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1).add({days: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).add({years: 1})"));
    }

    // round requires an options argument, and at least one of smallestUnit/largestUnit
    @Test
    public void test_round_requires_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).round()"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).round({})"));
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

    // subtract can produce an exactly-zero (blank) result
    @Test
    public void test_subtract_to_zero() {
        assertEquals("PT0S", str("new Temporal.Duration(0, 0, 0, 1).subtract({hours: 24}).toString()"));
        assertTrue(bool("new Temporal.Duration(0, 0, 0, 1).subtract({hours: 24}).blank"));
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

    // toJSON/toLocaleString mirror toString with no options
    @Test
    public void test_to_json_and_locale_string() {
        assertEquals("PT1H", str("new Temporal.Duration(0, 0, 0, 0, 1).toJSON()"));
        assertEquals("PT1H", str("new Temporal.Duration(0, 0, 0, 0, 1).toLocaleString()"));
        assertEquals("\"PT1H\"", str("JSON.stringify(new Temporal.Duration(0, 0, 0, 0, 1))"));
    }

    // toString honors fractionalSecondDigits
    @Test
    public void test_to_string_fractional_digits() {
        assertEquals("PT1.500S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500).toString({fractionalSecondDigits: 3})"));
        assertEquals("PT0.000S", str("new Temporal.Duration().toString({fractionalSecondDigits: 3})"));
    }

    // valueOf always throws
    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration().valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(1) + 1"));
    }

    // Every prototype method brand-checks its receiver
    @Test
    public void test_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Duration.prototype.toString.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Object.getOwnPropertyDescriptor(" + "Temporal.Duration.prototype, 'years').get.call({})"));
    }

    // Temporal.Duration.from accepts a Duration, a string, or a duration-like object
    @Test
    public void test_from() {
        assertEquals("P1D", str("Temporal.Duration.from('P1D').toString()"));
        assertEquals("P1D", str("Temporal.Duration.from({days: 1}).toString()"));
        assertEquals("P1D", str("Temporal.Duration.from(new Temporal.Duration(0,0,0,1)).toString()"));
    }

    // from rejects an empty duration-like object and a non-duration primitive
    @Test
    public void test_from_rejects_invalid_input() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Duration.from({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Duration.from(1)"));
    }

    // A seconds component with more than 9 fractional digits truncates rather than rounds
    @Test
    public void test_from_string_truncates_excess_fraction_digits() {
        assertEquals("PT1.123456789S", str("Temporal.Duration.from('PT1.123456789999S').toString()"));
    }

    // A seconds-looking component with no trailing designator, or a "." with no digits after it,
    // is a malformed duration string
    @Test
    public void test_from_string_malformed_seconds_component() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.Duration.from('PT1.5')"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.Duration.from('PT1.S')"));
    }

    // Temporal.Duration.compare orders by total duration length
    @Test
    public void test_compare() {
        assertEquals(-1, num("Temporal.Duration.compare({hours: 1}, {hours: 2})"));
        assertEquals(1, num("Temporal.Duration.compare({hours: 2}, {hours: 1})"));
        assertEquals(0, num("Temporal.Duration.compare({minutes: 60}, {hours: 1})"));
    }

    // compare accepts an ISO duration string operand too
    @Test
    public void test_compare_accepts_a_string_operand() {
        assertEquals(0, num("Temporal.Duration.compare('PT1H', {minutes: 60})"));
        assertEquals(0, num("Temporal.Duration.compare(new Temporal.Duration(0, 0, 0, 0, 2), 'PT2H')"));
    }

    // compare on durations with a year/month/week component is a documented RangeError, unless the
    // two operands are literally identical (no calendar math needed to know a value equals itself)
    @Test
    public void test_compare_calendar_dependent_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.Duration.compare({years: 1, hours: 1}, {years: 1, hours: 2})"));
    }

    // identical operands (including a shared nonzero year/month/week) compare equal without
    // requiring relativeTo
    @Test
    public void test_compare_identical_operands_with_calendar_fields() {
        assertEquals(0, num("Temporal.Duration.compare(new Temporal.Duration(5, 5, 5, 5, 5, 5, 5, 5, 5, 5),"
                + " new Temporal.Duration(5, 5, 5, 5, 5, 5, 5, 5, 5, 5))"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter
                        .run("Temporal.Duration.compare(" + "new Temporal.Duration(5, 5, 5, 5, 5, 5, 5, 5, 5, 5),"
                                + " new Temporal.Duration(5, 5, 5, 5, 4, 65, 5, 5, 5, 5))"));
    }

    // add/subtract accept an ISO duration string operand too
    @Test
    public void test_add_accepts_a_string_operand() {
        assertEquals("P1DT1H", str("new Temporal.Duration(0, 0, 0, 1).add('PT1H').toString()"));
    }

    // An explicit `undefined` constructor argument behaves like an omitted one
    @Test
    public void test_explicit_undefined_argument() {
        assertEquals("P1Y3D", str("new Temporal.Duration(1, undefined, 0, 3).toString()"));
    }

    // round/total/toString reject a non-object, non-string options argument
    @Test
    public void test_options_must_be_object_or_string() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0,0,0,1).round(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0,0,0,1).total(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0,0,0,1).toString(5)"));
    }

    // round accepts a largestUnit alone (smallestUnit defaults to nanoseconds, i.e. exact carrying)
    @Test
    public void test_round_with_only_largest_unit() {
        assertEquals("PT25H", str("new Temporal.Duration(0, 0, 0, 1, 1).round({largestUnit: 'hours'}).toString()"));
    }

    // toString's smallestUnit is restricted to the fractional-second units
    @Test
    public void test_to_string_smallest_unit() {
        assertEquals("PT1.500S", str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500)"
                + ".toString({smallestUnit: 'milliseconds'}).toString()"));
        assertEquals("PT1.500000S", str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500)"
                + ".toString({smallestUnit: 'microseconds'}).toString()"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0,0,0,1).toString({smallestUnit: 'hours'})"));
    }

    // toString's fractionalSecondDigits accepts the "auto" shorthand
    @Test
    public void test_to_string_fractional_digits_auto() {
        assertEquals("PT1.5S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500).toString({fractionalSecondDigits: 'auto'})"));
    }

    // toStringTag reports the spec name
    @Test
    public void test_to_string_tag() {
        assertEquals("[object Temporal.Duration]", str("Object.prototype.toString.call(new Temporal.Duration())"));
    }

    // Subclassing works via the generic native-super mechanism
    @Test
    public void test_subclass() {
        assertEquals("P1D", str("class D extends Temporal.Duration {}" + "new D(0, 0, 0, 1).toString()"));
    }
}
