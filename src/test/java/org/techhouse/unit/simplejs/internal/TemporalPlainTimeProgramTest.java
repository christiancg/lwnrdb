package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TemporalPlainTimeProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Construction, field getters and toString round-trip through a real script
    @Test
    public void test_construction_and_field_getters() {
        assertEquals("9,30,0,0,0,0", str("""
                const t = new Temporal.PlainTime(9, 30);
                [t.hour, t.minute, t.second, t.millisecond, t.microsecond, t.nanosecond].join(',')
                """));
        assertEquals("09:30:00", str("new Temporal.PlainTime(9, 30).toString()"));
    }

    // add/subtract compose and wrap around midnight, matching the direct builtin-level assertions
    @Test
    public void test_add_and_subtract_compose() {
        assertEquals("00:30:00", str("""
                const t = new Temporal.PlainTime(23, 0, 0);
                t.add({ hours: 1, minutes: 30 }).toString()
                """));
        assertEquals("22:30:00", str("""
                const t = new Temporal.PlainTime(0, 0, 0);
                t.subtract({ hours: 1, minutes: 30 }).toString()
                """));
    }

    // round() honours an explicit roundingMode
    @Test
    public void test_round_with_rounding_mode() {
        assertEquals("12:34:00", str("""
                const t = new Temporal.PlainTime(12, 34, 56);
                t.round({ smallestUnit: 'minute', roundingMode: 'floor' }).toString()
                """));
    }

    // compare() orders three instants consistently with equals()
    @Test
    public void test_compare_is_consistent_with_equals() {
        assertTrue(bool("""
                const a = new Temporal.PlainTime(1, 0, 0);
                const b = new Temporal.PlainTime(2, 0, 0);
                Temporal.PlainTime.compare(a, b) < 0
                    && Temporal.PlainTime.compare(b, a) > 0
                    && Temporal.PlainTime.compare(a, a) === 0
                    && a.equals(a)
                    && !a.equals(b)
                """));
    }

    // A PlainTime is immutable: with()/add() never mutate the receiver
    @Test
    public void test_immutability() {
        assertTrue(bool("""
                const t = new Temporal.PlainTime(1, 2, 3);
                t.with({ hour: 9 });
                t.add({ hours: 5 });
                t.hour === 1 && t.minute === 2 && t.second === 3
                """));
    }

    // A thrown RangeError (out-of-range constructor field) surfaces through SimpleJs.run's error
    // contract as {isError: true, errorName: "RangeError"}, exactly like every other builtin.
    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("new Temporal.PlainTime(24, 0, 0)", SimpleHostBindings.empty());
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // A thrown TypeError (valueOf(), which unconditionally rejects primitive coercion) surfaces
    // through SimpleJs.run's error contract as well.
    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("new Temporal.PlainTime(1).valueOf()", SimpleHostBindings.empty());
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    // A successful script's return value round-trips through SimpleJs.run's EJson contract as a
    // plain ISO time string (Temporal values have no dedicated EJson representation).
    @Test
    public void test_successful_result_serializes_as_a_string() {
        final var result = new SimpleJs().run("return new Temporal.PlainTime(1, 2, 3).toString()",
                SimpleHostBindings.empty());
        assertFalse(result.isError());
        assertEquals("\"01:02:03\"", new org.techhouse.ejson.EJson().toJson(result.getValue()));
    }

    // toPlainDateTime accepts a duck-typed {year, month, day} object (no Temporal.PlainDate type
    // exists in this phase) and combines it into an ISO date-time string.
    @Test
    public void test_to_plain_date_time_with_a_duck_typed_date() {
        assertEquals("2024-03-15T09:30:00", str("""
                const t = new Temporal.PlainTime(9, 30);
                t.toPlainDateTime({ year: 2024, month: 3, day: 15 }).toString()
                """));
    }

    // toZonedDateTime is a documented, narrow gap in this phase (Temporal.Instant/ZonedDateTime are
    // not implemented yet), so it throws a catchable TypeError rather than silently misbehaving.
    @Test
    public void test_to_zoned_date_time_is_not_yet_supported() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).toZonedDateTime({})"));
    }
}
