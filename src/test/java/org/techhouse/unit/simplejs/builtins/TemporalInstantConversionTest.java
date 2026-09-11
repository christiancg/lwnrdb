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

public class TemporalInstantConversionTest {
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

    // Temporal.Instant.from accepts an instance or an ISO instant string
    @Test
    public void test_from() {
        assertTrue(bool("Temporal.Instant.from(new Temporal.Instant(5n)).equals(new Temporal.Instant(5n))"));
        assertEquals(0, num("Temporal.Instant.from('1970-01-01T00:00:00Z').epochMilliseconds"));
        assertEquals(3600000, num("Temporal.Instant.from('1970-01-01T01:00:00+00:00').epochMilliseconds"));
        assertEquals(0, num("Temporal.Instant.from('1970-01-01T01:00:00+01:00').epochMilliseconds"));
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

    // toZonedDateTimeISO rejects a non-string timeZone argument
    @Test
    public void test_to_zoned_date_time_iso_non_string_zone() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).toZonedDateTimeISO(5)"));
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

    // fractionalSecondDigits floors a non-integer Number rather than rejecting it outright (only the
    // floored value's range is validated)
    @Test
    public void test_to_string_fractional_second_digits_invalid() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({fractionalSecondDigits: -1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({fractionalSecondDigits: 10})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({fractionalSecondDigits: -0.6})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({fractionalSecondDigits: NaN})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({fractionalSecondDigits: null})"));
    }

    @Test
    public void test_to_string_smallest_unit() {
        assertEquals("1970-01-01T00:00:00.500Z",
                str("new Temporal.Instant(500499999n).toString({smallestUnit: 'millisecond'})"));
        assertEquals("1970-01-01T00:00:01Z",
                str("new Temporal.Instant(500999999n).toString({smallestUnit: 'second', roundingMode: 'ceil'})"));
    }

    // toString's smallestUnit also accepts "microsecond" and "nanosecond"
    @Test
    public void test_to_string_smallest_unit_microsecond_and_nanosecond() {
        assertEquals("1970-01-01T00:00:00.500499Z",
                str("new Temporal.Instant(500499999n).toString({smallestUnit: 'microsecond'})"));
        assertEquals("1970-01-01T00:00:00.500499999Z",
                str("new Temporal.Instant(500499999n).toString({smallestUnit: 'nanosecond'})"));
    }

    // toString accepts "minute" (unlike round(), which is hour-and-smaller) - only day-or-coarser is
    // rejected
    @Test
    public void test_to_string_smallest_unit_rejects_larger_than_second() {
        assertEquals("1970-01-01T00:00Z", str("new Temporal.Instant(0n).toString({smallestUnit: 'minute'})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({smallestUnit: 'hour'})"));
    }

    @Test
    public void test_to_string_time_zone_option() {
        assertEquals("1970-01-01T05:00:00+05:00", str("new Temporal.Instant(0n).toString({timeZone: '+05:00'})"));
        assertEquals("1969-12-31T19:00:00-05:00", str("new Temporal.Instant(0n).toString({timeZone: '-05:00'})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).toString({timeZone: '+01:02:03'})"));
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

    // Instant.from/parseOffsetNanos handles an offset with a seconds component and a fractional part
    @Test
    public void test_from_offset_with_seconds_and_fraction() {
        assertEquals("1970-01-01T00:00:00Z",
                str("Temporal.Instant.from('1970-01-01T01:02:03.5+01:02:03.5').toString()"));
    }

    // A negative offset written with the Unicode minus sign parses the same as an ASCII hyphen
    @Test
    public void test_from_offset_unicode_minus() {
        // Every Temporal string sign is ASCII-only; U+2212 MINUS SIGN is rejected everywhere,
        // including a UTC offset's sign, per test262.
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.Instant.from('1970-01-01T00:00:00−01:00')"));
    }

    @Test
    public void test_to_locale_string() {
        assertEquals("1970-01-01T00:00:00Z", str("new Temporal.Instant(0n).toLocaleString()"));
    }

    @Test
    public void test_to_zoned_date_time_iso_returns_real_zoned_date_time() {
        assertTrue(bool("new Temporal.Instant(0n).toZonedDateTimeISO('UTC') instanceof Temporal.ZonedDateTime"));
    }
}
