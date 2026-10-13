package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptErrorNames;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class ScriptAllocationChargingTest {
    private static final long MEMORY_BUDGET = 8L * 1024 * 1024;
    private static final int ARRAY_CAP = 1000;
    private static final long WALL_CLOCK_MILLIS = 30_000L;

    private static ResourceLimits limits(long wallClockMillis, int maxArrayLength) {
        return new ResourceLimits(-1, wallClockMillis, 200, true, false, List.of(), -1, -1, false, false,
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.DEFAULT_MAX_LOG_LINES,
                ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, MEMORY_BUDGET, -1, -1, -1,
                ResourceLimits.DEFAULT_FETCH_MAX_CONCURRENT, maxArrayLength);
    }

    private static ScriptResult run(String source, long wallClockMillis, int maxArrayLength) {
        return new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), null, null, limits(wallClockMillis, maxArrayLength)));
    }

    private static ScriptResult run(String source) {
        return run(source, WALL_CLOCK_MILLIS, ARRAY_CAP);
    }

    private static void assertFailsWith(String errorName, ScriptResult result) {
        assertTrue(result.isError(), "expected " + errorName);
        assertEquals(errorName, result.getErrorName(), result.getErrorMessage());
    }

    private static void assertSucceeds(ScriptResult result) {
        assertFalse(result.isError(), result.getErrorName() + ": " + result.getErrorMessage());
    }

    @Test
    public void test_growing_an_array_through_its_length_is_charged() {
        assertFailsWith(ScriptErrorNames.MEMORY, run("const a = []; a.length = 1 << 23; return a.length;"));
        assertSucceeds(run("const a = []; a.length = 1000; return a.length;"));
    }

    @Test
    public void test_writing_far_past_the_end_of_an_array_is_charged() {
        assertFailsWith(ScriptErrorNames.MEMORY, run("const a = []; a[(1 << 23) - 1] = 1; return a.length;"));
        assertSucceeds(run("const a = []; a[999] = 1; return a.length;"));
    }

    @Test
    public void test_defining_a_length_through_define_property_is_charged() {
        assertFailsWith(ScriptErrorNames.MEMORY,
                run("const a = []; Object.defineProperty(a, 'length', { value: 1 << 23 }); return a.length;"));
    }

    @Test
    public void test_function_apply_refuses_an_array_like_past_the_cap() {
        assertFailsWith(ScriptErrorNames.ARRAY_TOO_LARGE, run("return Math.max.apply(null, { length: 2 ** 31 });"));
        final var atCap = run("return (function () { return arguments.length; }).apply(null, { length: 1000 });");
        assertSucceeds(atCap);
        assertEquals(1000, atCap.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_function_apply_with_an_array_releases_its_argument_list() {
        final var result = run("const args = new Array(1000).fill(1); let total = 0;"
                + " for (let i = 0; i < 1000; i++) { total += Math.max.apply(null, args); } return total;");
        assertSucceeds(result);
        assertEquals(1000, result.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_reflect_apply_and_construct_refuse_an_array_like_past_the_cap() {
        assertFailsWith(ScriptErrorNames.ARRAY_TOO_LARGE,
                run("return Reflect.apply(Math.max, null, { length: 1001 });"));
        assertFailsWith(ScriptErrorNames.ARRAY_TOO_LARGE,
                run("return Reflect.construct(Array, { length: 1001 }).length;"));
        assertSucceeds(run("return Reflect.apply(Array.of, null, { length: 1000 }).length;"));
    }

    @Test
    public void test_vector_from_refuses_an_array_like_past_the_cap() {
        assertFailsWith(ScriptErrorNames.ARRAY_TOO_LARGE, run("return Vector.from({ length: 2 ** 30 });"));
        final var result = run("return Vector.from({ length: 2, 0: 1, 1: 2 }).toString();");
        assertSucceeds(result);
    }

    @Test
    public void test_iterator_windows_charges_every_window_it_copies() {
        assertFailsWith(ScriptErrorNames.MEMORY, run("function* g() { for (let i = 0; i < 200000; i++) { yield i; } }"
                + " let n = 0; for (const w of g().windows(100000)) { n++; } return n;"));
        final var small = run("let n = 0; for (const w of [1, 2, 3, 4].values().windows(2)) { n++; } return n;");
        assertSucceeds(small);
        assertEquals(3, small.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_array_buffer_slice_from_minus_infinity_is_charged() {
        assertFailsWith(ScriptErrorNames.MEMORY, run("const b = new ArrayBuffer(1 << 20); const kept = [];"
                + " for (let i = 0; i < 16; i++) { kept.push(b.slice(-Infinity)); } return kept.length;"));
        final var sliced = run("return new ArrayBuffer(8).slice(-Infinity, 4).byteLength;");
        assertSucceeds(sliced);
        assertEquals(4, sliced.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_typed_array_sort_to_reversed_and_set_charge_their_boxed_copies() {
        assertFailsWith(ScriptErrorNames.MEMORY, run("const t = new Uint8Array(1 << 20); t.sort(); return 1;"));
        assertFailsWith(ScriptErrorNames.MEMORY,
                run("const t = new Uint8Array(1 << 20); return t.toReversed().length;"));
        assertFailsWith(ScriptErrorNames.MEMORY,
                run("const t = new Uint8Array(1 << 20); t.set(new Uint8Array(1 << 20)); return 1;"));
        final var sorted = run("return Array.from(new Uint8Array([3, 1, 2]).sort()).join(',');");
        assertSucceeds(sorted);
        assertEquals("1,2,3", sorted.getValue().asJsonString().getValue());
    }

    @Test
    public void test_typed_array_set_still_refuses_a_source_that_is_too_large() {
        final var result = run(
                "try { new Uint8Array(2).set(new Uint8Array(3)); return 'set'; }" + " catch (e) { return e.name; }");
        assertSucceeds(result);
        assertEquals("RangeError", result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_an_indented_json_stringify_is_charged_for_its_indentation() {
        assertFailsWith(ScriptErrorNames.MEMORY, run("let o = new Array(20000).fill(1);"
                + " for (let i = 0; i < 200; i++) { o = [o]; } return JSON.stringify(o, null, 10).length;"));
        final var small = run("return JSON.stringify({ a: [1, 2] }, null, 10);");
        assertSucceeds(small);
    }

    @Test
    public void test_json_stringify_charges_a_shared_string_per_occurrence() {
        assertFailsWith(ScriptErrorNames.MEMORY, run("const s = 'x'.repeat(1 << 16);"
                + " const a = new Array(1000).fill(s); return JSON.stringify(a).length;"));
    }

    @Test
    public void test_string_raw_is_charged_for_what_it_builds() {
        assertFailsWith(ScriptErrorNames.MEMORY,
                run("const s = 'x'.repeat(1 << 16);" + " return String.raw({ raw: new Array(200).fill(s) }).length;"));
        final var small = run("return String.raw({ raw: ['a', 'b', 'c'] }, 1, 2);");
        assertSucceeds(small);
        assertEquals("a1b2c", small.getValue().asJsonString().getValue());
    }

    @Test
    public void test_normalize_is_charged_for_its_worst_case_expansion() {
        assertFailsWith(ScriptErrorNames.MEMORY, run("return '\\uFDFA'.repeat(1 << 18).normalize('NFKC').length;"));
        final var ascii = run("const s = 'x'.repeat(1 << 20); let n = 0;"
                + " for (let i = 0; i < 20; i++) { n += s.normalize('NFKC').length; } return n;");
        assertSucceeds(ascii);
        assertEquals(20 << 20, ascii.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_array_from_async_refuses_an_array_like_past_the_cap() {
        assertFailsWith(ScriptErrorNames.ARRAY_TOO_LARGE,
                run("return (await Array.fromAsync({ length: 2 ** 40 })).length;"));
        final var atCap = run("return (await Array.fromAsync({ length: 1000 })).length;");
        assertSucceeds(atCap);
        assertEquals(1000, atCap.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_array_from_async_over_a_huge_array_like_honours_the_deadline() {
        final var start = System.nanoTime();
        final var result = run("return (await Array.fromAsync({ length: 2 ** 40 })).length;", 300L,
                ResourceLimits.UNLIMITED_ARRAY_LENGTH);
        final var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.isError());
        assertTrue(elapsedMillis < 10_000, "ran " + elapsedMillis + " ms past a 300 ms deadline");
    }

    @Test
    public void test_the_array_length_check_needs_a_running_interpreter() {
        assertDoesNotThrow(() -> InterpreterOps.requireArrayLength(null, Long.MAX_VALUE));
        assertDoesNotThrow(() -> InterpreterOps.chargeArrayLike(null, Long.MAX_VALUE));
    }

    @Test
    public void test_an_unlimited_cap_leaves_array_likes_alone() {
        final var result = run("return Reflect.apply(Array.of, null, { length: 5000 }).length;", WALL_CLOCK_MILLIS,
                ResourceLimits.UNLIMITED_ARRAY_LENGTH);
        assertSucceeds(result);
    }
}
