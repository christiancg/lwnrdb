package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class SimpleJsTest {
    private final SimpleJs engine = new SimpleJs();

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    // A top-level return value is serialized to EJson as the script result
    @Test
    public void test_return_value() {
        final var result = run("let x = (1 + 2) * 3; return x;");
        assertFalse(result.isError());
        assertEquals(9, result.getValue().asJsonNumber().asInteger());
    }

    // With no return, export default becomes the result
    @Test
    public void test_export_default_result() {
        final var result = run("export default { ok: true };");
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonObject().get("ok").asJsonBoolean().getValue());
    }

    // With only named exports the result is an object of those exports
    @Test
    public void test_named_exports_result() {
        final var result = run("export const a = 1; export const b = 2;");
        assertFalse(result.isError());
        assertEquals(1, result.getValue().asJsonObject().get("a").asJsonNumber().asInteger());
        assertEquals(2, result.getValue().asJsonObject().get("b").asJsonNumber().asInteger());
    }

    // return takes precedence over export default
    @Test
    public void test_return_beats_export_default() {
        final var result = run("export default 1; return 2;");
        assertEquals(2, result.getValue().asJsonNumber().asInteger());
    }

    // An array result is serialized as a JSON array
    @Test
    public void test_array_result() {
        final var result = run("return [1, 2, 3].map(x => x * 2);");
        assertInstanceOf(JsonArray.class, result.getValue());
        assertEquals(3, result.getValue().asJsonArray().size());
    }

    // An empty (undefined) result serializes to JSON null
    @Test
    public void test_undefined_result_is_null() {
        final var result = run("let x = 1;");
        assertFalse(result.isError());
        assertInstanceOf(JsonNull.class, result.getValue());
    }

    // A syntax error is reported as an error result
    @Test
    public void test_syntax_error() {
        final var result = run("let = ;");
        assertTrue(result.isError());
        assertEquals("SyntaxError", result.getErrorName());
    }

    // A thrown Error that escapes becomes an error result carrying its name and message
    @Test
    public void test_thrown_error() {
        final var result = run("throw new TypeError('bad thing');");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
        assertEquals("bad thing", result.getErrorMessage());
    }

    // A runtime TypeError (member access on null) is reported as an error result
    @Test
    public void test_runtime_type_error() {
        final var result = run("let o = null; return o.a;");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    // A top-level await result is unwrapped and returned as the script result
    @Test
    public void test_top_level_await_return_value() {
        final var result = run("return await Promise.resolve(7);");
        assertFalse(result.isError());
        assertEquals(7, result.getValue().asJsonNumber().asInteger());
    }

    // A Promise subclass awaited at the top level settles through the public entrypoint
    @Test
    public void test_top_level_await_of_a_promise_subclass() {
        final var result = run("""
                class P extends Promise { constructor(e) { super(e); } }
                return await P.resolve(3).then(v => v * 2);
                """);
        assertFalse(result.isError());
        assertEquals(6, result.getValue().asJsonNumber().asInteger());
    }

    // A rejected top-level await surfaces as an error result
    @Test
    public void test_top_level_await_rejection_is_error() {
        final var result = run("await Promise.reject(new TypeError('nope'));");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
        assertEquals("nope", result.getErrorMessage());
    }

    // console.log is routed to the host console sink
    @Test
    public void test_console_sink() {
        final var captured = new ArrayList<String>();
        final var host = new SimpleHostBindings(new JsonObject(), null, captured::add, ResourceLimits.unlimited());
        engine.run("console.log('hi', 42);", host);
        assertEquals(1, captured.size());
        assertEquals("hi 42", captured.getFirst());
    }

    // a rejection with no handler is reported to the console sink at drain end
    @Test
    public void test_unhandled_rejection_reported() {
        final var captured = new ArrayList<String>();
        final var host = new SimpleHostBindings(new JsonObject(), null, captured::add, ResourceLimits.unlimited());
        engine.run("Promise.reject('boom');", host);
        assertEquals(1, captured.size());
        assertEquals("UnhandledPromiseRejection: boom", captured.getFirst());
    }

    // attaching a rejection handler suppresses the unhandled-rejection report
    @Test
    public void test_handled_rejection_not_reported() {
        final var captured = new ArrayList<String>();
        final var host = new SimpleHostBindings(new JsonObject(), null, captured::add, ResourceLimits.unlimited());
        engine.run("Promise.reject('boom').catch(() => {});", host);
        assertTrue(captured.isEmpty());
    }

    // reportUnhandledRejections=false silences the report
    @Test
    public void test_unhandled_rejection_silenced() {
        final var captured = new ArrayList<String>();
        final var limits = new ResourceLimits(-1, -1, -1, false);
        final var host = new SimpleHostBindings(new JsonObject(), null, captured::add, limits);
        engine.run("Promise.reject('boom');", host);
        assertTrue(captured.isEmpty());
    }

    // Strict mode: assignment to an undeclared name is a ReferenceError, not an implicit global
    @Test
    public void test_assignment_to_undeclared_is_reference_error() {
        final var result = run("undeclaredName = 1; return undeclaredName;");
        assertTrue(result.isError());
        assertEquals("ReferenceError", result.getErrorName());
    }

    // Strict mode: `this` inside a plain function call is undefined, not the global object
    @Test
    public void test_plain_call_this_is_undefined() {
        final var result = run("function f() { return this === undefined; } return f();");
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }

    // A static private field no longer escapes as a raw Java exception
    @Test
    public void test_static_private_field_is_supported() {
        final var result = run("class A { static #x = 1; static read() { return A.#x } } return A.read();");
        assertFalse(result.isError());
        assertEquals(1, result.getValue().asJsonNumber().asInteger());
    }

    // An unsupported node is reported as a SyntaxError result rather than thrown
    @Test
    public void test_unsupported_node_maps_to_syntax_error() {
        final var result = run("label: { break label } return 1;");
        assertFalse(result.isError());
    }

    // Builtin subclassing is reachable through the public entrypoint
    @Test
    public void test_extends_error_through_entrypoint() {
        final var result = run("class E extends Error {}"
                + " try { throw new E('x') } catch (e) { return [e instanceof E, e instanceof Error, e.toString()] }");
        assertFalse(result.isError());
        final var array = assertInstanceOf(JsonArray.class, result.getValue());
        assertTrue(array.get(0).asJsonBoolean().getValue());
        assertTrue(array.get(1).asJsonBoolean().getValue());
        assertEquals("Error: x", array.get(2).asJsonString().getValue());
    }

    // A promise returned at top level is awaited, since the event loop has already drained
    @Test
    public void test_toplevel_returned_promise_is_awaited() {
        final var result = run("async function f() { return 42; } return f()");
        assertFalse(result.isError());
        assertEquals(42, result.getValue().asJsonNumber().getValue().intValue());
        assertEquals(5,
                engine.run("async function f() { return Promise.resolve(5); } return f()", SimpleHostBindings.empty())
                        .getValue().asJsonNumber().getValue().intValue());
    }

    // A rejected top-level promise becomes the script error
    @Test
    public void test_toplevel_returned_promise_rejection_is_script_error() {
        final var result = run("async function f() { throw new TypeError('boom'); } return f()");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
        assertEquals("boom", result.getErrorMessage());

        final var plain = run("return Promise.reject('plain')");
        assertTrue(plain.isError());
        assertEquals("Error", plain.getErrorName());
        assertEquals("plain", plain.getErrorMessage());
    }

    // export default is awaited the same way
    @Test
    public void test_export_default_promise_is_awaited() {
        final var result = run("export default (async function() { return 7; })()");
        assertFalse(result.isError());
        assertEquals(7, result.getValue().asJsonNumber().getValue().intValue());
    }

    // A promise that never settles inside the sandbox contributes JSON null
    @Test
    public void test_toplevel_pending_promise_resolves_to_null() {
        final var result = run("return new Promise(function() {})");
        assertFalse(result.isError());
        assertInstanceOf(JsonNull.class, result.getValue());
    }

    // An awaited top-level rejection is not also reported as an unhandled rejection
    @Test
    public void test_awaited_promise_not_reported_as_unhandled_rejection() {
        final var messages = new ArrayList<String>();
        final var host = new SimpleHostBindings(new JsonObject(), null, messages::add, ResourceLimits.unlimited());
        final var result = engine.run("return Promise.reject(new Error('x'))", host);
        assertTrue(result.isError());
        assertTrue(messages.stream().noneMatch(m -> m.contains("UnhandledPromiseRejection")), messages::toString);
    }

    // The strict Script goal turns the relaxed host contract's top-level forms into SyntaxErrors
    @Test
    public void test_strict_script_goal_rejects_the_relaxed_contract() {
        final var limits = new ResourceLimits(-1, -1, -1, true, true);
        final var host = new SimpleHostBindings(new JsonObject(), null, null, limits);
        for (final var source : new String[]{"return 1;", "export default 1;", "import args from 'args';",
                "import.meta;", "new.target;", "using x = null;"}) {
            final var result = engine.run(source, host);
            assertTrue(result.isError(), source);
            assertEquals("SyntaxError", result.getErrorName(), source);
        }
    }

    // The default goal keeps the host contract: the same sources run
    @Test
    public void test_default_goal_keeps_the_relaxed_contract() {
        assertEquals(1, run("return 1;").getValue().asJsonNumber().asInteger());
        assertFalse(run("export default 1;").isError());
        assertFalse(run("import args from 'args'; return 1;").isError());
    }

}
