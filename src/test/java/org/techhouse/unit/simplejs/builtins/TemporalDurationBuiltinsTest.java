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

    // with rejects a duration-like object with none of the ten recognized properties present
    @Test
    public void test_with_rejects_empty_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).with({})"));
    }

    // add/subtract reject an argument that is neither a Temporal.Duration, an ISO duration string,
    // nor a duration-like object
    @Test
    public void test_add_rejects_non_duration_argument() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 1).add(42)"));
    }

    // Accessing an unrecognized member returns undefined rather than throwing
    @Test
    public void test_unrecognized_member_is_undefined() {
        assertTrue(bool("typeof new Temporal.Duration(0, 0, 0, 1).notAMethod === 'undefined'"));
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
    public void test_from_string_rejects_excess_fraction_digits() {
        // TemporalDecimalFraction is bounded to 1-9 digits; a 10th digit is a RangeError, not a
        // value to silently truncate, per test262.
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.Duration.from('PT1.123456789999S')"));
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

    // IsValidDuration: years/months/weeks cap at 2**32, and a huge finite field (even one that would
    // silently overflow a (long) cast) is still correctly rejected
    @Test
    public void test_out_of_range_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(4294967296)"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 9007199254740992)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(Number.MAX_VALUE)"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 0, 0, Number.MAX_VALUE)"));
    }

    // A duration string component that parses to Infinity (an astronomically long digit run) is a
    // RangeError, not silently accepted as a same-signed "huge" value
    @Test
    public void test_from_string_infinite_component_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.Duration.from('P' + '9'.repeat(400) + 'Y')"));
    }

    // add()/subtract() balance the result no coarser than the RECEIVER's own finest nonzero unit,
    // regardless of the argument's shape - not a fixed day/second floor
    @Test
    public void test_add_balances_to_receivers_own_finest_unit() {
        assertEquals("PT0.000002S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 0, 1).add({microseconds: 1})" + ".toString()"));
        assertEquals("PT1.000001S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).add({microseconds: 1}).toString()"));
        assertEquals("PT0.001S", str("new Temporal.Duration().add({milliseconds: 1}).toString()"));
    }

    // with()/round() reject a result that balances out of IsValidDuration's range, exactly like the
    // raw constructor
    @Test
    public void test_with_result_out_of_range_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).with({seconds: 9007199254740992})"));
    }

    @Test
    public void test_round_result_out_of_range_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.Duration.from({seconds: Number.MAX_SAFE_INTEGER, "
                        + "nanoseconds: 999999999}).round({smallestUnit: 'seconds'})"));
    }

    // toString()/toJSON() never wrap their rounded result in a JsTemporalDuration, so they must
    // independently reject a roundingMode that pushes the tail out of range
    @Test
    public void test_to_string_smallest_unit_result_out_of_range_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.Duration.from({seconds: Number.MAX_SAFE_INTEGER, milliseconds: 999})"
                        + ".toString({smallestUnit: 'seconds', roundingMode: 'ceil'})"));
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

    // Reflect.construct(Temporal.Duration, args, newTarget) reads newTarget's "prototype" (propagating
    // a throw from a poisoned getter) rather than skipping straight to the intrinsic prototype
    @Test
    public void test_reflect_construct_propagates_new_target_prototype_getter_throw() {
        assertEquals("boom", str("var newTarget = Object.defineProperty(function(){}.bind(), 'prototype', "
                + "{get(){ throw 'boom'; }});" + "var caught;"
                + "try { Reflect.construct(Temporal.Duration, [], newTarget); } catch (e) { caught = e; }" + "caught"));
    }

    // A newTarget naming a genuinely different prototype links the constructed instance to it (a
    // wrapper), while every prototype method/accessor keeps working through the wrapped primitive
    @Test
    public void test_reflect_construct_links_to_new_target_prototype() {
        assertTrue(bool("var proto = {marker: true};" + "var newTarget = function(){}; newTarget.prototype = proto;"
                + "var instance = Reflect.construct(Temporal.Duration, [0, 0, 0, 1], newTarget);"
                + "Object.getPrototypeOf(instance) === proto && instance.days === 1"));
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
        assertTrue(bool(
                "var d = new Temporal.Duration(0, 0, 0, -3, -12);" + "var zdt = new Temporal.ZonedDateTime(0n, 'UTC');"
                        + "var r = d.round({smallestUnit: 'hours', roundingIncrement: 8, roundingMode: 'halfEven', "
                        + "relativeTo: zdt});" + "r.sign === -1"));
    }

    // months/weeks are independently checked against the same 2**32 limit as years
    @Test
    public void test_months_and_weeks_out_of_range_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 4294967296)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Duration(0, 0, 4294967296)"));
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

    // add()/subtract() also correctly resolve hours-only and minutes-only as the receiver's own
    // finest/default largest unit (not just days or seconds)
    @Test
    public void test_add_default_largest_unit_hours_and_minutes() {
        assertEquals("PT2H", str("new Temporal.Duration(0, 0, 0, 0, 1).add({hours: 1}).toString()"));
        assertEquals("PT2M", str("new Temporal.Duration(0, 0, 0, 0, 0, 1).add({minutes: 1}).toString()"));
    }

    // toString's fractionalSecondDigits rejects a non-number, non-"auto" value, and a number outside
    // 0-9
    @Test
    public void test_to_string_fractional_digits_invalid_value_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).toString({" + "fractionalSecondDigits: 'bogus'})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).toString({" + "fractionalSecondDigits: 15})"));
    }
}
