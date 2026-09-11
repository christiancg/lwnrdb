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

    // add()/subtract() also accept a real Temporal.Duration instance or an ISO 8601 duration string
    @Test
    public void test_add_accepts_duration_instance_and_string() {
        assertEquals(1000, num("new Temporal.Instant(0n).add(Temporal.Duration.from({seconds: 1})).epochMilliseconds"));
        assertEquals(1000, num("new Temporal.Instant(0n).add('PT1S').epochMilliseconds"));
    }

    // add()/subtract() reject an argument that is neither a Temporal.Duration, an ISO duration
    // string, nor a duration-like object
    @Test
    public void test_add_rejects_non_duration_argument() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).add(42)"));
    }

    // add()/subtract() reject a duration-like object with none of the ten recognized properties
    @Test
    public void test_add_rejects_duration_like_with_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).add({})"));
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

    // The halfCeil/halfFloor/halfEven modes' non-tie branches: a remainder clearly above half rounds
    // away from zero regardless of mode, and a remainder clearly below half keeps the truncated
    // quotient regardless of mode - only an exact tie (already covered elsewhere) distinguishes them
    @Test
    public void test_round_half_modes_non_tie_remainder() {
        final var setup = "new Temporal.Instant(%dn).round({smallestUnit: 'nanosecond', roundingIncrement: 10, "
                + "roundingMode: '%s'}).epochNanoseconds.toString()";
        assertEquals("20", str(String.format(setup, 17, "halfCeil")));
        assertEquals("10", str(String.format(setup, 12, "halfCeil")));
        assertEquals("20", str(String.format(setup, 17, "halfFloor")));
        assertEquals("10", str(String.format(setup, 12, "halfFloor")));
        assertEquals("20", str(String.format(setup, 17, "halfEven")));
        assertEquals("10", str(String.format(setup, 12, "halfEven")));
    }

    // round() computes every mode via RoundNumberToIncrementAsIfPositive: the raw (possibly negative)
    // epochNanoseconds value is bracketed by its true floor/ceiling multiples of the increment, and
    // "trunc"/"expand" resolve to those brackets directly (same as "floor"/"ceil") rather than
    // shrinking/growing the value's magnitude the way they would for a signed Duration.
    @Test
    public void test_round_all_modes_negative() {
        final var setup = "new Temporal.Instant(%dn).round({smallestUnit: 'nanosecond', roundingIncrement: 10, "
                + "roundingMode: '%s'}).epochNanoseconds.toString()";
        assertEquals("-10", str(String.format(setup, -13, "ceil")));
        assertEquals("-20", str(String.format(setup, -13, "floor")));
        assertEquals("-20", str(String.format(setup, -13, "trunc")));
        assertEquals("-10", str(String.format(setup, -15, "halfExpand")));
    }

    // "expand" rounds toward the ceiling bracket unconditionally, for both a positive and a negative
    // instant (not "away from zero", which is Duration's own, sign-aware convention)
    @Test
    public void test_round_expand() {
        assertEquals("3600000000000",
                str("new Temporal.Instant(1n).round({smallestUnit: 'hour', roundingMode: 'expand'})"
                        + ".epochNanoseconds.toString()"));
        assertEquals("0", str("new Temporal.Instant(-1n).round({smallestUnit: 'hour', roundingMode: 'expand'})"
                + ".epochNanoseconds.toString()"));
    }

    // round() accepts every fixed-length time unit through hour (day and coarser are rejected - a
    // "day" has no fixed length without a calendar/time zone attached)
    @Test
    public void test_round_every_unit() {
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

    // round() rejects a smallestUnit larger than hour (a bare string is a shorthand for
    // {smallestUnit: string}, so only a genuinely non-object/non-string argument is a TypeError)
    @Test
    public void test_round_rejects_invalid_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).round(5)"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).round({smallestUnit: 'month'})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).round({smallestUnit: 'day'})"));
    }

    // roundingIncrement must truncate to a positive integer that evenly divides the unit's
    // fit-into-a-day maximum (e.g. up to 24 for "hour", not just the immediately-larger unit)
    @Test
    public void test_round_invalid_increment() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).round({smallestUnit: 'hour', roundingIncrement: 5})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Instant(0n).round({smallestUnit: 'second', roundingIncrement: 0})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Instant(0n).round({smallestUnit: 'second', roundingIncrement: 0.5})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.Instant(0n).round({smallestUnit: 'second', roundingIncrement: NaN})"));
    }

    // A method receiving a wrapped subclass instance (produced via Reflect.construct with a foreign
    // newTarget) unwraps it back to the real Temporal.Instant through its wrapped primitive
    @Test
    public void test_wrapped_instant_argument_is_unwrapped() {
        assertTrue(bool("""
                var Ctor = function() {};
                var wrapped = Reflect.construct(Temporal.Instant, [5n], Ctor);
                Temporal.Instant.compare(wrapped, new Temporal.Instant(5n)) === 0
                """));
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

    // A largestUnit of "hour" decomposes an hour-and-larger difference into an hours field
    @Test
    public void test_until_with_hour_largest_unit() {
        assertTrue(bool("var d = Temporal.Instant.fromEpochMilliseconds(0)"
                + ".until(Temporal.Instant.fromEpochMilliseconds(5400000), {largestUnit: 'hour'}); "
                + "d.hours === 1 && d.minutes === 30"));
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

    @Test
    public void test_round_rejects_non_object_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Instant(0n).round(5)"));
    }

    @Test
    public void test_round_string_shorthand() {
        assertEquals("1970-01-01T01:00:00Z", str("new Temporal.Instant(1800000000000n).round('hour').toString()"));
    }

    @Test
    public void test_round_rejects_invalid_rounding_increment_for_day() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).round({smallestUnit: 'day', roundingIncrement: 2})"));
    }

    @Test
    public void test_round_half_ceil_half_floor_half_even() {
        // 1500 is exactly halfway between 1000 and 2000; halfCeil breaks the tie toward +infinity.
        assertEquals("2000", str("new Temporal.Instant(1500n).round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 1000, roundingMode: 'halfCeil'}).epochNanoseconds.toString()"));
        // -1500 is exactly halfway between -2000 and -1000; halfFloor breaks the tie toward -infinity.
        assertEquals("-2000", str("new Temporal.Instant(-1500n).round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 1000, roundingMode: 'halfFloor'}).epochNanoseconds.toString()"));
        // 2500 is exactly halfway between 2000 and 3000; halfEven picks the even multiple (2).
        assertEquals("2000", str("new Temporal.Instant(2500n).round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 1000, roundingMode: 'halfEven'}).epochNanoseconds.toString()"));
        // -1500 is exactly halfway between -2000 and -1000; halfCeil breaks the tie toward
        // +infinity, i.e. the less-negative side this time (the mirror image of the positive case).
        assertEquals("-1000", str("new Temporal.Instant(-1500n).round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 1000, roundingMode: 'halfCeil'}).epochNanoseconds.toString()"));
        // 1500 is exactly halfway between 1000 and 2000; halfFloor breaks the tie toward -infinity,
        // i.e. the smaller side this time.
        assertEquals("1000", str("new Temporal.Instant(1500n).round({smallestUnit: 'nanosecond', "
                + "roundingIncrement: 1000, roundingMode: 'halfFloor'}).epochNanoseconds.toString()"));
    }

    @Test
    public void test_until_since_reject_non_object_options() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).until(new Temporal.Instant(1n), 5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.Instant(0n).since(new Temporal.Instant(1n), 5)"));
    }
}
