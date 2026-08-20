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

    // toZonedDateTimeISO is a documented narrow approximation (no real Temporal.ZonedDateTime yet)
    @Test
    public void test_to_zoned_date_time_iso_approximation() {
        assertEquals("UTC", str("new Temporal.Instant(0n).toZonedDateTimeISO('UTC').timeZoneId"));
        assertEquals("iso8601", str("new Temporal.Instant(0n).toZonedDateTimeISO('UTC').calendarId"));
    }
}
