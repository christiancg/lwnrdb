package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

// Real script snippets through SimpleJs.run, mirroring DateProgramTest but for Temporal.Instant
// (phase T6), including how a thrown RangeError/TypeError surfaces through SimpleJs.run's error
// contract (ScriptResult.isError()/getErrorName()/getErrorMessage()).
public class TemporalInstantProgramTest {
    private final SimpleJs engine = new SimpleJs();

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    // Constructing from a BigInt epoch-nanoseconds value and reading it back
    @Test
    public void test_construct_from_epoch_nanoseconds() {
        final var result = run("return new Temporal.Instant(1000000000n).epochMilliseconds;");
        assertFalse(result.isError());
        assertEquals(1000, result.getValue().asJsonNumber().asInteger());
    }

    // epochMilliseconds and epochNanoseconds agree on the same instant
    @Test
    public void test_epoch_getters_agree() {
        final var result = run("""
                const instant = new Temporal.Instant(2500000000n);
                return instant.epochMilliseconds === 2500
                    && instant.epochNanoseconds.toString() === '2500000000';
                """);
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }

    // toString renders the canonical UTC ISO instant string
    @Test
    public void test_to_string() {
        final var result = run("return new Temporal.Instant(0n).toString();");
        assertFalse(result.isError());
        assertEquals("1970-01-01T00:00:00Z", result.getValue().asJsonString().getValue());
    }

    // add()/subtract()/until() compose consistently for a whole-second offset
    @Test
    public void test_add_subtract_until() {
        final var result = run("""
                const start = new Temporal.Instant(0n);
                const later = start.add({seconds: 10});
                const back = later.subtract({seconds: 10});
                const diff = start.until(later);
                return back.equals(start) && diff.seconds === 10;
                """);
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }

    // Temporal.Instant.compare orders two instants consistently with equals()
    @Test
    public void test_compare_consistent_with_equals() {
        final var result = run("""
                const a = new Temporal.Instant(0n);
                const b = new Temporal.Instant(1n);
                return Temporal.Instant.compare(a, b) < 0
                    && Temporal.Instant.compare(b, a) > 0
                    && Temporal.Instant.compare(a, a) === 0
                    && a.equals(a);
                """);
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }

    // Temporal.Instant.fromEpochMilliseconds round-trips through epochMilliseconds
    @Test
    public void test_from_epoch_milliseconds() {
        final var result = run("return Temporal.Instant.fromEpochMilliseconds(123456).epochMilliseconds;");
        assertFalse(result.isError());
        assertEquals(123456, result.getValue().asJsonNumber().asInteger());
    }

    // A plain number (not BigInt) epochNanoseconds argument is a TypeError, surfaced through the
    // engine's error contract rather than thrown as a raw Java exception.
    @Test
    public void test_constructor_type_error_surfaces() {
        final var result = run("return new Temporal.Instant(0).epochMilliseconds;");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    // An out-of-range epoch nanoseconds value is a RangeError, surfaced through the error contract
    @Test
    public void test_out_of_range_range_error_surfaces() {
        final var result = run("return new Temporal.Instant(100000000000000000000000n);");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // A script-level try/catch can observe and recover from a Temporal.Instant error
    @Test
    public void test_error_is_catchable_in_script() {
        final var result = run("""
                try {
                    new Temporal.Instant(0).epochMilliseconds;
                    return 'no throw';
                } catch (e) {
                    return e.name;
                }
                """);
        assertFalse(result.isError());
        assertEquals("TypeError", result.getValue().asJsonString().getValue());
    }

    // round() with an explicit unit/increment/mode combination, exercised end-to-end through the
    // engine's error/result contract rather than the direct builtin-level helper.
    @Test
    public void test_round_with_options_end_to_end() {
        final var result = run("""
                const rounded = Temporal.Instant.fromEpochMilliseconds(1500)
                    .round({ smallestUnit: 'second', roundingMode: 'floor' });
                return rounded.epochMilliseconds;
                """);
        assertFalse(result.isError());
        assertEquals(1000, result.getValue().asJsonNumber().asInteger());
    }

    // toString() with a timeZone option renders a non-UTC offset
    @Test
    public void test_to_string_with_time_zone_option() {
        final var result = run("return new Temporal.Instant(0n).toString({ timeZone: '+02:00' });");
        assertFalse(result.isError());
        assertEquals("1970-01-01T02:00:00+02:00", result.getValue().asJsonString().getValue());
    }

    // An invalid time zone identifier surfaces as a catchable RangeError
    @Test
    public void test_invalid_time_zone_surfaces_range_error() {
        final var result = run("return new Temporal.Instant(0n).toString({ timeZone: 'Not/AZone' });");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // until()/since() report a duration decomposed against a custom largestUnit
    @Test
    public void test_until_with_largest_unit_end_to_end() {
        final var result = run("""
                const a = Temporal.Instant.fromEpochMilliseconds(0);
                const b = Temporal.Instant.fromEpochMilliseconds(90000);
                const d = a.until(b, { largestUnit: 'minute' });
                return d.minutes === 1 && d.seconds === 30;
                """);
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }
}
