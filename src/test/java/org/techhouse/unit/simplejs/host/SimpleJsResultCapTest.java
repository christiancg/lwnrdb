package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class SimpleJsResultCapTest {
    private static final String BIG_RESULT = "return new Array(2000).fill('0123456789');";
    private final SimpleJs simpleJs = new SimpleJs();

    private static ResourceLimits capped(long maxResultBytes) {
        return new ResourceLimits(-1, -1, -1, true, false, List.of(), -1, -1, false, false,
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.DEFAULT_MAX_LOG_LINES,
                ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, -1, maxResultBytes, -1, -1);
    }

    private ScriptResult run(String source, ResourceLimits limits) {
        return simpleJs.run(source, new SimpleHostBindings(new JsonObject(), null, null, limits));
    }

    @Test
    public void test_oversized_result_fails_with_script_result_too_large_error() {
        final var result = run(BIG_RESULT, capped(256));
        assertTrue(result.isError());
        assertEquals("ScriptResultTooLargeError", result.getErrorName());
        assertTrue(result.getErrorMessage().contains("exceeds the maximum of 256 bytes"), result.getErrorMessage());
    }

    @Test
    public void test_result_within_cap_succeeds() {
        final var result = run("return { ok: true };", capped(4096));
        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.getValue().asJsonObject().get("ok").asJsonBoolean().getValue());
    }

    @Test
    public void test_unlimited_result_bytes_skips_the_check() {
        final var result = run(BIG_RESULT, ResourceLimits.unlimited());
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(2000, result.getValue().asJsonArray().size());
    }

    @Test
    public void test_logs_are_still_returned_on_an_oversized_result() {
        final var result = run("console.log('before the result'); " + BIG_RESULT, capped(256));
        assertTrue(result.isError());
        assertEquals(List.of("before the result"), result.getLogs());
    }

    @Test
    public void test_oversized_result_from_compiled_script_also_fails() {
        final var compiled = simpleJs.compile(BIG_RESULT, false);
        final var result = simpleJs.run(compiled, new SimpleHostBindings(new JsonObject(), null, null, capped(256)));
        assertTrue(result.isError());
        assertEquals("ScriptResultTooLargeError", result.getErrorName());
    }

    // A result exactly at the cap passes and one byte over fails, so the boundary is not off by one
    @Test
    public void test_result_exactly_at_the_cap_passes() {
        final var size = run("return 'abcdef';", ResourceLimits.unlimited()).getValue();
        final var exact = org.techhouse.simplejs.values.EJsonInterop.estimatedBytes(size);
        assertFalse(run("return 'abcdef';", capped(exact)).isError());
        assertTrue(run("return 'abcdef';", capped(exact - 1)).isError());
    }

    // An undefined result is JSON null: a few bytes, so no realistic cap rejects it
    @Test
    public void test_undefined_result_is_never_rejected() {
        final var result = run("const x = 1;", capped(64));
        assertFalse(result.isError(), result.getErrorMessage());
    }
}
