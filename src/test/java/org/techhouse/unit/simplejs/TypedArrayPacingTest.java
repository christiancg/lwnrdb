package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class TypedArrayPacingTest {
    private final SimpleJs simpleJs = new SimpleJs();

    private static ResourceLimits withInstructionBudget(long instructions) {
        return new ResourceLimits(instructions, -1L, -1, true, false, List.of(), -1, -1, false, false, 16, 1000, 1000,
                -1L, -1, -1, -1, 8, -1);
    }

    private ScriptResultName run(String source, long instructions) {
        final var result = simpleJs.run(simpleJs.compile(source, false),
                new SimpleHostBindings(new JsonObject(), null, null, withInstructionBudget(instructions)));
        return new ScriptResultName(result.isError(), result.getErrorName(), result.getErrorMessage());
    }

    private record ScriptResultName(boolean error, String name, String message) {
    }

    @Test
    public void test_set_from_a_large_array_like_is_paced() {
        final var result = run("const t = new Uint8Array(3000000); t.set({length: 3000000}); return 0;", 200000);
        assertTrue(result.error(), "a large set() must be bounded");
        assertEquals("ScriptLimitError", result.name(), result.message());
    }

    @Test
    public void test_constructing_from_a_large_array_like_is_paced() {
        final var result = run("const t = new Uint8Array({length: 3000000}); return t.length;", 200000);
        assertTrue(result.error(), "a large array-like construction must be bounded");
        assertEquals("ScriptLimitError", result.name(), result.message());
    }

    @Test
    public void test_small_array_like_operations_still_succeed() {
        final var set = run("const t = new Uint8Array(4); t.set({length: 4, 0: 1, 1: 2}); return t[1];", 10000000);
        assertFalse(set.error(), set.message());
        final var from = run("const t = new Uint8Array({length: 3, 0: 7}); return t[0];", 10000000);
        assertFalse(from.error(), from.message());
    }
}
