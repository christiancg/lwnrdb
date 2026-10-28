package org.techhouse.unit.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.CompiledScript;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class ScriptAllocationBudgetTest {
    private static final long TRIVIAL_RUN = 57_170;
    private static final long STRING_APPEND_100K = 10_060_000;
    private static final long LOOP_ITERATION = 44;
    private static final long FUNCTION_CALL = 415;
    private static final long REDUCE_PER_DOC = 2_800;
    private static final long MAP_PER_DOC = 2_750;
    private static final int RUNS = 5;
    private static final int WARM_UP_RUNS = 10;
    private static final int LOOP_COUNT = 200_000;
    private static final int REDUCE_DOCS = 2_000;
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
            .getThreadMXBean();
    private static final SimpleJs SIMPLE_JS = new SimpleJs();
    private static final SimpleHostBindings HOST = new SimpleHostBindings(new JsonObject(), null, null,
            new ResourceLimits(Long.MAX_VALUE / 4, 600_000, 10_000, Long.MAX_VALUE / 4));

    @FunctionalInterface
    private interface Action {
        void run();
    }

    private static long cheapestOf(Action action) {
        for (var i = 0; i < WARM_UP_RUNS; i++) {
            action.run();
        }
        var cheapest = Long.MAX_VALUE;
        for (var i = 0; i < RUNS; i++) {
            final var before = THREADS.getTotalThreadAllocatedBytes();
            action.run();
            cheapest = Math.min(cheapest, THREADS.getTotalThreadAllocatedBytes() - before);
        }
        return cheapest;
    }

    private static long perRun(String source, int repeat) {
        final CompiledScript compiled = SIMPLE_JS.compile(source, false);
        return cheapestOf(() -> {
            for (var i = 0; i < repeat; i++) {
                final var result = SIMPLE_JS.run(compiled, HOST);
                assertFalse(result.isError(), result::getErrorMessage);
            }
        }) / repeat;
    }

    private static void assertWithin(String label, long budget, long measured) {
        assertTrue(measured <= budget, label + " allocated " + measured + " bytes, budget " + budget);
    }

    @Test
    public void test_a_trivial_run_builds_only_what_it_touches() {
        for (var i = 0; i < 2_000; i++) {
            SIMPLE_JS.run(SIMPLE_JS.compile("return 1;", false), HOST);
        }
        assertWithin("trivial run", TRIVIAL_RUN, perRun("return 1;", 200));
    }

    @Test
    public void test_a_hundred_thousand_appends_stay_linear() {
        assertWithin("100k appends", STRING_APPEND_100K,
                perRun("let s = ''; for (let i = 0; i < 100000; i++) { s += 'x'; } return s.length;", 1));
    }

    @Test
    public void test_a_loop_iteration_and_a_call_stay_small() {
        final var empty = perRun("for (let i = 0; i < " + LOOP_COUNT + "; i++) {} return 0;", 1);
        assertWithin("loop iteration", LOOP_ITERATION, empty / LOOP_COUNT);
        final var calls = perRun("function f(x) { return x + 1; } let s = 0;" + " for (let i = 0; i < " + LOOP_COUNT
                + "; i++) { s = f(s); } return s;", 1);
        assertWithin("function call", FUNCTION_CALL, (calls - empty) / LOOP_COUNT);
    }

    @Test
    public void test_a_reduce_row_converts_only_the_fields_it_reads() {
        final var document = new JsonObject();
        document.add("_id", new JsonString("d"));
        for (var i = 0; i < 12; i++) {
            document.add("field" + i, new JsonString("value-" + i));
        }
        document.add("price", new JsonNumber(3));
        try (var callable = SIMPLE_JS.openCallable("export default (acc, doc) => acc + doc.price;", HOST)) {
            final var measured = cheapestOf(() -> {
                var accumulator = (org.techhouse.ejson.elements.JsonBaseElement) new JsonNumber(0);
                for (var i = 0; i < REDUCE_DOCS; i++) {
                    accumulator = callable.apply(accumulator, document);
                }
                assertEquals(3.0 * REDUCE_DOCS, accumulator.asJsonNumber().getValue().doubleValue());
            }) / REDUCE_DOCS;
            assertWithin("reduce per document", REDUCE_PER_DOC, measured);
        }
    }

    private static JsonObject wideRow() {
        final var document = new JsonObject();
        document.add("_id", new JsonString("d"));
        for (var i = 0; i < 12; i++) {
            document.add("field" + i, new JsonString("value-" + i));
        }
        document.add("price", new JsonNumber(3));
        return document;
    }

    @Test
    public void test_a_map_row_converts_only_the_fields_it_reads() {
        final var document = wideRow();
        try (var callable = SIMPLE_JS.openCallable("export default (doc) => doc.price * 2;", HOST)) {
            final var measured = cheapestOf(() -> {
                for (var i = 0; i < REDUCE_DOCS; i++) {
                    assertEquals(6.0,
                            callable.apply(document, Set.of("total")).asJsonNumber().getValue().doubleValue());
                }
            }) / REDUCE_DOCS;
            assertWithin("map per document", MAP_PER_DOC, measured);
        }
    }
}
