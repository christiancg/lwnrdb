package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.PipelineScriptContext;
import org.techhouse.simplejs.exceptions.ScriptCallableException;

public class PipelineScriptContextTest {
    private static JsonObject document() {
        final var document = new JsonObject();
        document.add("price", new JsonNumber((double) 1));
        return document;
    }

    // A source repeated across steps or inside a conjunction is opened once, so its state (and its share
    // of the budget) is shared rather than duplicated.
    @Test
    public void test_opens_one_callable_per_distinct_source() {
        try (var context = new PipelineScriptContext()) {
            final var source = "let seen = 0; export default (doc) => ++seen;";
            final var first = context.callableFor(source);
            final var second = context.callableFor(source);
            assertSame(first, second);
            assertEquals(1d, first.apply(document()).asJsonNumber().getValue().doubleValue());
            assertEquals(2d, second.apply(document()).asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_distinct_sources_get_distinct_callables() {
        try (var context = new PipelineScriptContext()) {
            assertNotSame(context.callableFor("export default (doc) => 1;"),
                    context.callableFor("export default (doc) => 2;"));
        }
    }

    @Test
    public void test_closes_every_callable() {
        final var context = new PipelineScriptContext();
        final var first = context.callableFor("export default (doc) => 1;");
        final var second = context.callableFor("export default (doc) => 2;");
        context.close();
        assertThrows(RuntimeException.class, () -> first.apply(document()));
        assertThrows(RuntimeException.class, () -> second.apply(document()));
    }

    @Test
    public void test_close_without_any_callable_is_a_no_op() {
        assertDoesNotThrow(() -> new PipelineScriptContext().close());
    }

    @Test
    public void test_invalid_source_reports_a_script_failure() {
        try (var context = new PipelineScriptContext()) {
            final var error = assertThrows(ScriptCallableException.class,
                    () -> context.callableFor("export default (doc) => {"));
            assertEquals("SyntaxError", error.getErrorName());
        }
    }
}
