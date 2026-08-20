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

public class TemporalInstantBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Calling Temporal.Instant as a plain function (no `new`) is a TypeError
    @Test
    public void test_constructor_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Instant(0n)"));
    }

    // A plain number epochNanoseconds argument is rejected: only a BigInt is accepted
    @Test
    public void test_constructor_requires_bigint() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0)"));
    }

    // A valid epochNanoseconds BigInt constructs a Temporal.Instant object
    @Test
    public void test_constructor_with_bigint() {
        assertEquals("object", str("typeof new Temporal.Instant(0n)"));
    }

    // Object.prototype.toString reports the real [Symbol.toStringTag], "[object Temporal.Instant]"
    @Test
    public void test_to_string_tag() {
        assertEquals("[object Temporal.Instant]", str("Object.prototype.toString.call(new Temporal.Instant(0n))"));
    }

    // An epochNanoseconds value past the representable range is a RangeError
    @Test
    public void test_constructor_out_of_range() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(100000000000000000000000n)"));
    }

    // epochMilliseconds/epochNanoseconds are accessor properties (no parens), not methods
    @Test
    public void test_epoch_accessors() {
        assertEquals(1000, num("new Temporal.Instant(1000000000n).epochMilliseconds"));
        assertEquals("1000000000", str("new Temporal.Instant(1000000000n).epochNanoseconds.toString()"));
        assertEquals("bigint", str("typeof new Temporal.Instant(0n).epochNanoseconds"));
    }

    // epochMilliseconds rounds toward negative infinity for sub-millisecond precision
    @Test
    public void test_epoch_milliseconds_rounds_toward_negative_infinity() {
        assertEquals(-1, num("new Temporal.Instant(-1n).epochMilliseconds"));
    }

    // Temporal.Instant.fromEpochMilliseconds/fromEpochNanoseconds statics
    @Test
    public void test_from_epoch_statics() {
        assertEquals(1000, num("Temporal.Instant.fromEpochMilliseconds(1000).epochMilliseconds"));
        assertEquals(1000, num("Temporal.Instant.fromEpochNanoseconds(1000000000n).epochMilliseconds"));
    }

    // The epoch instant's canonical toString is UTC "Z"
    @Test
    public void test_to_string_epoch() {
        assertEquals("1970-01-01T00:00:00Z", str("new Temporal.Instant(0n).toString()"));
    }

    // toString renders a fractional second when the instant carries sub-second precision
    @Test
    public void test_to_string_with_fraction() {
        assertEquals("1970-01-01T00:00:00.5Z", str("new Temporal.Instant(500000000n).toString()"));
    }

    // toJSON matches the default toString
    @Test
    public void test_to_json() {
        assertTrue(bool("new Temporal.Instant(0n).toJSON() === new Temporal.Instant(0n).toString()"));
    }

    // valueOf always throws, per spec - Instant arithmetic must go through compare()/equals()
    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n) + 1"));
    }

    // add()/subtract() accept a plain Duration-like object exposing time fields
    @Test
    public void test_add_and_subtract() {
        assertEquals(1000, num("new Temporal.Instant(0n).add({hours: 0, seconds: 1}).epochMilliseconds"));
        assertEquals(-1000, num("new Temporal.Instant(0n).subtract({seconds: 1}).epochMilliseconds"));
    }

    // add()/subtract() reject a non-zero calendar field (days/weeks/months/years)
    @Test
    public void test_add_rejects_calendar_fields() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).add({days: 1})"));
    }

    // equals() compares two instants for exact equality
    @Test
    public void test_equals() {
        assertTrue(bool("new Temporal.Instant(0n).equals(new Temporal.Instant(0n))"));
        assertTrue(bool("!new Temporal.Instant(0n).equals(new Temporal.Instant(1n))"));
        assertTrue(bool("new Temporal.Instant(0n).equals('1970-01-01T00:00:00Z')"));
    }

    // Temporal.Instant.compare orders two instants
    @Test
    public void test_compare() {
        assertEquals(-1, num("Temporal.Instant.compare(new Temporal.Instant(0n), new Temporal.Instant(1n))"));
        assertEquals(1, num("Temporal.Instant.compare(new Temporal.Instant(1n), new Temporal.Instant(0n))"));
        assertEquals(0, num("Temporal.Instant.compare(new Temporal.Instant(1n), new Temporal.Instant(1n))"));
    }

    // Temporal.Instant.from accepts an instance or an ISO instant string
    @Test
    public void test_from() {
        assertTrue(bool("Temporal.Instant.from(new Temporal.Instant(5n)).equals(new Temporal.Instant(5n))"));
        assertEquals(0, num("Temporal.Instant.from('1970-01-01T00:00:00Z').epochMilliseconds"));
        assertEquals(3600000, num("Temporal.Instant.from('1970-01-01T01:00:00+00:00').epochMilliseconds"));
        assertEquals(0, num("Temporal.Instant.from('1970-01-01T01:00:00+01:00').epochMilliseconds"));
    }

    // until()/since() report a time-unit-only duration between two instants
    @Test
    public void test_until_and_since() {
        assertEquals(1, num("new Temporal.Instant(0n).until(Temporal.Instant.fromEpochMilliseconds(1000)).seconds"));
        assertEquals(-1, num("new Temporal.Instant(0n).since(Temporal.Instant.fromEpochMilliseconds(1000)).seconds"));
    }

    // round() rounds to the nearest smallestUnit boundary
    @Test
    public void test_round() {
        assertEquals(2000,
                num("Temporal.Instant.fromEpochMilliseconds(1600).round({smallestUnit: 'second'}).epochMilliseconds"));
        assertEquals(1000,
                num("Temporal.Instant.fromEpochMilliseconds(1400).round({smallestUnit: 'second'}).epochMilliseconds"));
    }

    // round() requires a smallestUnit option
    @Test
    public void test_round_requires_smallest_unit() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).round({})"));
    }

    // Every prototype method brand-checks its receiver
    @Test
    public void test_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Instant.prototype.toString.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run(
                "Object.getOwnPropertyDescriptor(Temporal.Instant.prototype," + " 'epochMilliseconds').get.call({})"));
    }

    // toZonedDateTimeISO returns a real Temporal.ZonedDateTime (phase T7)
    @Test
    public void test_to_zoned_date_time_iso_real_instance() {
        assertEquals("UTC", str("new Temporal.Instant(0n).toZonedDateTimeISO('UTC').timeZoneId"));
        assertEquals("iso8601", str("new Temporal.Instant(0n).toZonedDateTimeISO('UTC').calendarId"));
        assertTrue(bool("new Temporal.Instant(0n).toZonedDateTimeISO('UTC') instanceof Temporal.ZonedDateTime"));
    }

    // toZonedDateTimeISO requires a timeZone argument
    @Test
    public void test_to_zoned_date_time_iso_requires_time_zone() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).toZonedDateTimeISO()"));
    }

    // toZonedDateTimeISO's result carries a working toString() rendering offset + bracketed id
    @Test
    public void test_to_zoned_date_time_iso_to_string() {
        assertEquals("1970-01-01T00:00:00+00:00[UTC]",
                str("new Temporal.Instant(0n).toZonedDateTimeISO('UTC').toString()"));
    }

    // toZonedDateTimeISO rejects an invalid time zone identifier
    @Test
    public void test_to_zoned_date_time_iso_invalid_zone() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toZonedDateTimeISO('Not/AZone')"));
    }

    // round() sweeps every rounding mode on the positive side of a tie/non-tie remainder
    @Test
    public void test_round_all_modes_positive() {
        final var setup = "new Temporal.Instant(%dn).round({smallestUnit: 'nanosecond', roundingIncrement: 10, "
                + "roundingMode: '%s'}).epochNanoseconds.toString()";
        assertEquals("20", str(String.format(setup, 13, "ceil")));
        assertEquals("10", str(String.format(setup, 13, "floor")));
        assertEquals("10", str(String.format(setup, 13, "trunc")));
        assertEquals("20", str(String.format(setup, 15, "halfExpand")));
        assertEquals("20", str(String.format(setup, 15, "halfCeil")));
        assertEquals("10", str(String.format(setup, 15, "halfFloor")));
        assertEquals("10", str(String.format(setup, 15, "halfTrunc")));
        assertEquals("20", str(String.format(setup, 15, "halfEven")));
        assertEquals("20", str(String.format(setup, 25, "halfEven")));
    }

    // round() sweeps ceil/floor/trunc on the negative side (asymmetric-toward-infinity behaviour)
    @Test
    public void test_round_all_modes_negative() {
        final var setup = "new Temporal.Instant(%dn).round({smallestUnit: 'nanosecond', roundingIncrement: 10, "
                + "roundingMode: '%s'}).epochNanoseconds.toString()";
        assertEquals("-10", str(String.format(setup, -13, "ceil")));
        assertEquals("-20", str(String.format(setup, -13, "floor")));
        assertEquals("-10", str(String.format(setup, -13, "trunc")));
        assertEquals("-20", str(String.format(setup, -15, "halfExpand")));
    }

    // "expand" rounds away from zero unconditionally, for both a positive and a negative instant
    @Test
    public void test_round_expand() {
        assertEquals("86400000000000",
                str("new Temporal.Instant(1n).round({smallestUnit: 'day', roundingMode: 'expand'})"
                        + ".epochNanoseconds.toString()"));
        assertEquals("-86400000000000",
                str("new Temporal.Instant(-1n).round({smallestUnit: 'day', roundingMode: 'expand'})"
                        + ".epochNanoseconds.toString()"));
    }

    // round() accepts every fixed-length time unit through hour, plus the calendar-independent "day"
    @Test
    public void test_round_every_unit() {
        assertEquals("86400000000000", str("new Temporal.Instant(1n).round({smallestUnit: 'day', roundingMode: 'ceil'})"
                + ".epochNanoseconds.toString()"));
        assertEquals("0", str("new Temporal.Instant(1n).round({smallestUnit: 'hour', roundingIncrement: 12, "
                + "roundingMode: 'floor'}).epochNanoseconds.toString()"));
        assertEquals("0", str("new Temporal.Instant(1n).round({smallestUnit: 'minute', roundingIncrement: 30, "
                + "roundingMode: 'floor'}).epochNanoseconds.toString()"));
        assertEquals("0", str("new Temporal.Instant(1n).round({smallestUnit: 'second', roundingIncrement: 30, "
                + "roundingMode: 'floor'}).epochNanoseconds.toString()"));
        assertEquals("0", str("new Temporal.Instant(1n).round({smallestUnit: 'millisecond', roundingIncrement: 500, "
                + "roundingMode: 'floor'}).epochNanoseconds.toString()"));
        assertEquals("0", str("new Temporal.Instant(1n).round({smallestUnit: 'microsecond', roundingIncrement: 500, "
                + "roundingMode: 'floor'}).epochNanoseconds.toString()"));
        assertEquals("1",
                str("new Temporal.Instant(1n).round({smallestUnit: 'nanosecond'}).epochNanoseconds.toString()"));
    }

    // round() rejects a smallestUnit larger than day, and requires an options object at all
    @Test
    public void test_round_rejects_invalid_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).round('second')"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).round({smallestUnit: 'month'})"));
    }

    // roundingIncrement must be a positive integer that evenly divides the unit's maximum
    @Test
    public void test_round_invalid_increment() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).round({smallestUnit: 'hour', roundingIncrement: 5})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).round({smallestUnit: 'day', roundingIncrement: 2})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Instant(0n).round({smallestUnit: 'second', roundingIncrement: 0})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Instant(0n).round({smallestUnit: 'second', roundingIncrement: 1.5})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Instant(0n).round({smallestUnit: 'second', roundingIncrement: NaN})"));
    }

    // toString() honours fractionalSecondDigits (numeric and "auto"), smallestUnit, roundingMode and
    // timeZone options
    @Test
    public void test_to_string_fractional_second_digits() {
        assertEquals("1970-01-01T00:00:00.500000000Z",
                str("new Temporal.Instant(500000000n).toString({fractionalSecondDigits: 9})"));
        assertEquals("1970-01-01T00:00:00Z",
                str("new Temporal.Instant(500000000n).toString({fractionalSecondDigits: 0})"));
        assertEquals("1970-01-01T00:00:00.5Z",
                str("new Temporal.Instant(500000000n).toString({fractionalSecondDigits: 'auto'})"));
    }

    @Test
    public void test_to_string_fractional_second_digits_invalid() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({fractionalSecondDigits: -1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({fractionalSecondDigits: 10})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({fractionalSecondDigits: 2.5})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({fractionalSecondDigits: NaN})"));
    }

    @Test
    public void test_to_string_smallest_unit() {
        assertEquals("1970-01-01T00:00:00.500Z",
                str("new Temporal.Instant(500499999n).toString({smallestUnit: 'millisecond'})"));
        assertEquals("1970-01-01T00:00:01Z",
                str("new Temporal.Instant(500999999n).toString({smallestUnit: 'second', roundingMode: 'ceil'})"));
    }

    @Test
    public void test_to_string_smallest_unit_rejects_larger_than_second() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({smallestUnit: 'minute'})"));
    }

    @Test
    public void test_to_string_time_zone_option() {
        assertEquals("1970-01-01T05:00:00+05:00", str("new Temporal.Instant(0n).toString({timeZone: '+05:00'})"));
        assertEquals("1969-12-31T19:00:00-05:00", str("new Temporal.Instant(0n).toString({timeZone: '-05:00'})"));
        assertEquals("1970-01-01T01:02:03+01:02:03", str("new Temporal.Instant(0n).toString({timeZone: '+01:02:03'})"));
        assertEquals("1970-01-01T00:00:00+00:00", str("new Temporal.Instant(0n).toString({timeZone: 'UTC'})"));
    }

    @Test
    public void test_to_string_time_zone_iana() {
        assertEquals("string", str("typeof new Temporal.Instant(0n).toString({timeZone: 'America/New_York'})"));
    }

    @Test
    public void test_to_string_invalid_time_zone() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({timeZone: 'Not/AZone'})"));
    }

    // until()/since() reject a smallestUnit/largestUnit larger than hour, and a smallestUnit larger
    // than largestUnit
    @Test
    public void test_until_rejects_day_units() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Instant(0n).until(new Temporal.Instant(1n), {smallestUnit: 'day'})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Instant(0n).until(new Temporal.Instant(1n), {largestUnit: 'day'})"));
    }

    @Test
    public void test_until_rejects_smallest_larger_than_largest() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).until(new Temporal.Instant(1n), "
                        + "{smallestUnit: 'hour', largestUnit: 'second'})"));
    }

    // until() honours largestUnit/smallestUnit/roundingIncrement/roundingMode together
    @Test
    public void test_until_with_largest_unit() {
        assertTrue(bool("var d = Temporal.Instant.fromEpochMilliseconds(0)"
                + ".until(Temporal.Instant.fromEpochMilliseconds(90000), {largestUnit: 'minute'}); "
                + "d.minutes === 1 && d.seconds === 30"));
    }

    @Test
    public void test_until_with_rounding() {
        assertEquals(30,
                num("Temporal.Instant.fromEpochMilliseconds(0)"
                        + ".until(Temporal.Instant.fromEpochMilliseconds(20000), "
                        + "{smallestUnit: 'second', roundingIncrement: 30, roundingMode: 'halfExpand'}).seconds"));
    }

    // durationTimeNanos/numField reject non-integer, NaN and infinite duration fields
    @Test
    public void test_add_rejects_invalid_duration_fields() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).add({hours: 1.5})"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).add({hours: NaN})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).add({hours: Infinity})"));
    }

    // fromEpochNanoseconds requires a BigInt argument
    @Test
    public void test_from_epoch_nanoseconds_requires_bigint() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Instant.fromEpochNanoseconds(5)"));
    }

    // Temporal.Instant.from/compare reject a value that cannot be converted to a string (a Symbol)
    @Test
    public void test_from_rejects_unconvertible_value() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.Instant.from(Symbol())"));
    }

    // Reflect.construct(Temporal.Instant, args, newTarget) links the new instance's prototype to
    // newTarget.prototype (OrdinaryCreateFromConstructor) instead of the intrinsic Temporal.Instant
    // prototype.
    @Test
    public void test_reflect_construct_links_new_target_prototype() {
        assertTrue(bool("""
                var Ctor = function() {};
                var instance = Reflect.construct(Temporal.Instant, [5n], Ctor);
                Object.getPrototypeOf(instance) === Ctor.prototype
                    && Object.getOwnPropertyDescriptor(Temporal.Instant.prototype, "epochNanoseconds")
                        .get.call(instance).toString() === '5'
                """));
    }

    // A plain `new Temporal.Instant(...)` (no custom newTarget) keeps the ordinary prototype
    @Test
    public void test_plain_new_keeps_instant_prototype() {
        assertTrue(bool("Object.getPrototypeOf(new Temporal.Instant(0n)) === Temporal.Instant.prototype"));
    }

    // Instant.from/parseOffsetNanos handles an offset with a seconds component and a fractional part
    @Test
    public void test_from_offset_with_seconds_and_fraction() {
        assertEquals("1970-01-01T00:00:00Z",
                str("Temporal.Instant.from('1970-01-01T01:02:03.5+01:02:03.5').toString()"));
    }

    // A negative offset written with the Unicode minus sign parses the same as an ASCII hyphen
    @Test
    public void test_from_offset_unicode_minus() {
        assertEquals(3600000, num("Temporal.Instant.from('1970-01-01T00:00:00−01:00').epochMilliseconds"));
    }
}
