package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;

public class ScriptBudgetHardeningTest {
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final long MEMORY_BUDGET = 8L * 1024 * 1024;
    private static final String DAG = "let a = [1]; for (let i = 0; i < 40; i++) { a = [a, a]; }";

    private record Bindings(ResourceLimits limits, Consumer<String> console) implements HostBindings {
        @Override
        public JsonObject args() {
            return new JsonObject();
        }

        @Override
        public DatabaseAccess database() {
            return null;
        }
    }

    private static ScriptResult run(String source, long wallClockMillis, long memoryBudget) {
        return simpleJs.run(source, new Bindings(new ResourceLimits(-1, wallClockMillis, 200, memoryBudget), _ -> {
        }));
    }

    private static ScriptResult run(String source) {
        return run(source, 30_000L, MEMORY_BUDGET);
    }

    @Test
    public void test_object_keys_over_non_enumerable_indices_paces_by_position() {
        final var result = simpleJs.run("const a = []; for (let i = 0; i < 1023; i++) a[i] = i;"
                + " for (let i = 1023; i < 11023; i++) Object.defineProperty(a, i, { value: i, enumerable: false });"
                + " return Object.keys(a).length;",
                new Bindings(new ResourceLimits(2_000_000L, 30_000L, 200, MEMORY_BUDGET), _ -> {
                }));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(1023, result.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_a_bigint_parse_past_the_digit_limit_is_a_range_error() {
        final var result = run("return BigInt('9'.repeat(70000)).toString().length;");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_a_bigint_parse_at_the_digit_limit_succeeds() {
        final var result = run("return BigInt('9'.repeat(65536)).toString().length;");
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(65536, result.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_an_overlong_hex_number_literal_is_infinity_without_parsing() {
        final var result = run("const f = 'f'.repeat(70000); return [Number('0x' + f) === Infinity,"
                + " Number('0x' + '0'.repeat(70000) + '1'), Number('0x' + '0'.repeat(70000)),"
                + " Number.isNaN(Number('0x' + f + 'z'))];");
        assertFalse(result.isError(), result.getErrorMessage());
        final var values = result.getValue().asJsonArray();
        assertTrue(values.get(0).asJsonBoolean().getValue());
        assertEquals(1, values.get(1).asJsonNumber().getValue().intValue());
        assertEquals(0, values.get(2).asJsonNumber().getValue().intValue());
        assertTrue(values.get(3).asJsonBoolean().getValue());
    }

    @Test
    public void test_a_locale_compare_loop_over_large_strings_honours_the_deadline() {
        final var start = System.nanoTime();
        final var result = run("const s = 'x'.repeat(1 << 20), t = s + 'y'; while (true) { s.localeCompare(t); }", 300L,
                64L * 1024 * 1024);
        final var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName(), result.getErrorMessage());
        assertTrue(elapsedMillis < 10_000, "ran " + elapsedMillis + " ms past a 300 ms deadline");
    }

    @Test
    public void test_a_bigint_to_string_loop_honours_the_deadline() {
        final var result = run("const x = 7n ** 400000n; while (true) { x.toString(); }", 300L, 64L * 1024 * 1024);
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_stringifying_a_shared_reference_dag_fails_on_the_memory_budget() {
        final var result = run(DAG + " return JSON.stringify(a).length;");
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_returning_a_shared_reference_dag_fails_on_the_memory_budget() {
        final var result = run(DAG + " return a;");
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_flattening_a_shared_reference_dag_fails_on_the_memory_budget() {
        final var result = run(DAG + " return a.flat(Infinity).length;");
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_logging_a_shared_reference_dag_is_bounded() {
        final var lines = new ArrayList<String>();
        final var result = simpleJs.run(DAG + " console.log(a); return 1;",
                new Bindings(new ResourceLimits(-1, 30_000L, 200, MEMORY_BUDGET), lines::add));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().length() <= 1 << 16, "line of " + lines.getFirst().length() + " chars");
    }

    @Test
    public void test_logging_a_cyclic_or_very_deep_array_neither_overflows_nor_fails() {
        final var lines = new ArrayList<String>();
        final var result = simpleJs.run(
                "const a = [1]; a.push(a); console.log(a);"
                        + " let d = [2]; for (let i = 0; i < 100000; i++) { d = [d]; } console.log(d); return 1;",
                new Bindings(new ResourceLimits(-1, 30_000L, 200, 64L * 1024 * 1024), lines::add));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(List.of("1,", ""), lines);
    }

    @Test
    public void test_a_small_stringify_and_result_still_work_and_release_their_charge() {
        final var result = run(
                "let total = 0; for (let i = 0; i < 2000; i++) {"
                        + " total += JSON.stringify({a: [1, 2, 3], b: {c: 'x'}}).length; } return [total];",
                30_000L, 1024L * 1024);
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(2000 * 27, result.getValue().asJsonArray().get(0).asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_split_into_many_pieces_is_charged() {
        final var result = run("return 'a,'.repeat(1 << 20).split(',').length;", 30_000L, 4L * 1024 * 1024);
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_compiling_a_huge_regexp_is_charged_for_the_parse() {
        final var result = run("return new RegExp('a'.repeat(200000)).source.length;", 30_000L, 4L * 1024 * 1024);
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_a_simple_quantifier_rescanning_a_long_input_honours_the_deadline() {
        final var start = System.nanoTime();
        final var result = run("return /a{100000}/.test('a'.repeat(99999));", 300L, MEMORY_BUDGET);
        final var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName(), result.getErrorMessage());
        assertTrue(elapsedMillis < 5_000, "ran " + elapsedMillis + " ms past a 300 ms deadline");
    }

    @Test
    public void test_a_short_simple_quantifier_still_matches() {
        final var result = run("return [/a{3,}b/.test('xaaab'), /a{5}/.test('aaaa')];");
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(List.of(true, false), result.getValue().asJsonArray().asList().stream()
                .map(value -> value.asJsonBoolean().getValue()).toList());
    }

    private static final String HUGE_SPARSE = "const a = []; a.length = 2 ** 32 - 1; a[5] = 'five'; a[3e9] = 'wide';";
    private static final String SEARCH_OPERANDS = "const s = 'a'.repeat(1 << 22), t = 'a'.repeat(1 << 12) + 'b';";

    private static void assertTimesOut(String source) {
        final var start = System.nanoTime();
        final var result = run(source, 300L, -1L);
        final var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.isError());
        assertEquals("ScriptTimeoutError", result.getErrorName(), result.getErrorMessage());
        assertTrue(elapsedMillis < 5_000, "ran " + elapsedMillis + " ms past a 300 ms deadline");
    }

    private static String text(String source) {
        final var result = run(source, 30_000L, 64L * 1024 * 1024);
        assertFalse(result.isError(), result.getErrorMessage());
        return result.getValue().asJsonString().getValue();
    }

    @Test
    public void test_for_in_over_a_huge_sparse_length_honours_the_deadline() {
        assertEquals("5,3000000000",
                text(HUGE_SPARSE + " const keys = []; for (const k in a) keys.push(k);" + " return keys.join();"));
        assertTimesOut(HUGE_SPARSE + " while (true) { for (const k in a) {} }");
    }

    @Test
    public void test_object_keys_over_a_huge_sparse_length_honours_the_deadline() {
        assertEquals("5,3000000000|five,wide|5:five", text(HUGE_SPARSE + " return Object.keys(a).join() + '|'"
                + " + Object.values(a).join() + '|' + Object.entries(a)[0].join(':');"));
        assertTimesOut(HUGE_SPARSE + " while (true) { Object.keys(a); }");
    }

    @Test
    public void test_spread_of_a_huge_sparse_array_honours_the_memory_budget() {
        final var result = run(HUGE_SPARSE + " return [...a].length;");
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_spread_of_a_holey_array_still_yields_undefined_for_the_holes() {
        assertEquals("3,true", text("const a = [1, , 3]; const b = [...a]; return b.length + ',' + (1 in b);"));
    }

    @Test
    public void test_parseInt_of_a_long_digit_run_does_not_exceed_the_digit_limit() {
        final var start = System.nanoTime();
        assertEquals("true,12,true,-12",
                text("return [parseInt('9'.repeat(5000000)) === Infinity,"
                        + " parseInt('0'.repeat(70000) + '12'), parseInt('-' + '7'.repeat(70000)) === -Infinity,"
                        + " parseInt('-' + '0'.repeat(70000) + '12', 10)].join();"));
        final var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 5_000, "parseInt took " + elapsedMillis + " ms");
    }

    @Test
    public void test_string_indexOf_of_large_operands_honours_the_deadline() {
        assertEquals("-1,-1,false",
                text(SEARCH_OPERANDS + " return [s.indexOf(t), s.lastIndexOf(t), s.includes(t)].join();"));
        assertTimesOut(SEARCH_OPERANDS + " while (true) { s.indexOf(t); }");
    }

    @Test
    public void test_a_linear_search_finds_the_same_positions_as_a_direct_one() {
        final var needle = "const n = 'a'.repeat(15) + 'X';";
        assertEquals("2097137,2097137,true,-1", text(needle + " const s = 'a'.repeat(1 << 21) + 'X' + 'a'.repeat(100);"
                + " return [s.indexOf(n), s.indexOf(n, 2097137), s.includes(n, 10), s.indexOf(n, 2097138)].join();"));
        assertEquals("2097139,5,1500000,-1",
                text("const m = 'a'.repeat(15); const s = 'aX' + 'a'.repeat(1 << 21);"
                        + " return [s.lastIndexOf(m), s.lastIndexOf(m, 5), s.lastIndexOf(m, 1500000),"
                        + " s.lastIndexOf(m + 'X')].join();"));
    }

    @Test
    public void test_literal_split_and_replace_all_of_large_operands_stay_correct() {
        assertEquals("131073,1835008",
                text("const sep = 'x'.repeat(7) + '|';" + " const s = ('x'.repeat(20) + '|').repeat(1 << 17);"
                        + " return [s.split(sep).length, s.replaceAll(sep, '#').length].join();"));
    }

    @Test
    public void test_literal_split_of_large_operands_honours_the_deadline() {
        assertTimesOut(SEARCH_OPERANDS + " while (true) { s.split(t); }");
    }
}
