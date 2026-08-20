package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

// Real script snippets through the full engine, including SimpleJs.run's top-level error contract
// (mirroring DateErrorJsonProgramTest/DateProgramTest's shape for Temporal.Duration).
public class TemporalDurationProgramTest {
    private final SimpleJs engine = new SimpleJs();

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    // ISO 8601 duration string round-trip through the constructor's toString
    @Test
    public void test_iso_string_round_trip() {
        assertEquals("P1Y2M3W4DT5H6M7.5S", str("new Temporal.Duration(1, 2, 3, 4, 5, 6, 7, 500).toString()"));
    }

    // Temporal.Duration.from parses the same grammar back
    @Test
    public void test_from_string_round_trip() {
        assertEquals("P1Y2M3DT4H5M6.5S", str("Temporal.Duration.from('P1Y2M3DT4H5M6.5S').toString()"));
    }

    // add/subtract of calendar-independent durations balances carries across units
    @Test
    public void test_add_carries_across_units() {
        assertEquals("P1DT1H",
                str("new Temporal.Duration(0, 0, 0, 0, 23).add(new Temporal.Duration(0, 0, 0, 0, 2)).toString()"));
    }

    // round with an increment groups to the nearest multiple
    @Test
    public void test_round_with_increment() {
        assertEquals("PT10M", str("new Temporal.Duration(0, 0, 0, 0, 0, 12)"
                + ".round({smallestUnit: 'minutes', roundingIncrement: 5, roundingMode: 'floor'}).toString()"));
    }

    // total reports an exact fractional total, not an integer
    @Test
    public void test_total_is_fractional() {
        assertEquals(1.25, num("new Temporal.Duration(0, 0, 0, 0, 30).total({unit: 'days'})"));
    }

    // negated/abs/with are pure - they do not mutate the receiver
    @Test
    public void test_methods_are_immutable() {
        assertTrue(bool("var d = new Temporal.Duration(0, 0, 0, 1); d.negated();" + "d.days === 1"));
    }

    // Comparing durations via Temporal.Duration.compare inside a sort
    @Test
    public void test_compare_orders_durations() {
        assertEquals("PT30M,PT1H,PT2H",
                str("var ds = [{hours: 2}, {minutes: 30}, {hours: 1}]" + ".map(x => Temporal.Duration.from(x));"
                        + "ds.sort(Temporal.Duration.compare).map(d => d.toString()).join(',')"));
    }

    // A RangeError thrown constructing an invalid Duration surfaces through SimpleJs.run's error
    // contract as a RangeError with a message, not a Java stack trace.
    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = run("return new Temporal.Duration(1, -1);");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
        assertFalse(result.getErrorMessage().isEmpty());
    }

    // A TypeError (calling the constructor without new) surfaces the same way
    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = run("return Temporal.Duration();");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    // A successful script returns the Duration's ISO string via ordinary EJson interop
    @Test
    public void test_successful_result_via_simple_js_run() {
        final var result = run("return new Temporal.Duration(0, 0, 0, 1).toString();");
        assertFalse(result.isError());
        assertEquals("P1D", result.getValue().asJsonString().getValue());
    }

    // A Duration returned directly from top level serializes through EJsonInterop as its ISO string
    @Test
    public void test_duration_value_serializes_as_iso_string() {
        final var result = run("return new Temporal.Duration(0, 0, 0, 1);");
        assertFalse(result.isError());
        assertEquals("P1D", result.getValue().asJsonString().getValue());
    }
}
