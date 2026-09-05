package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.ScriptCallable;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

/**
 * The repeated-invocation seam: one module evaluation, many calls, and one budget shared by all of them.
 */
public class ScriptCallableTest {
    private final SimpleJs simpleJs = new SimpleJs();

    private static HostBindings host() {
        return new SimpleHostBindings(new JsonObject(), null, null, ResourceLimits.unlimited());
    }

    private static HostBindings host(ResourceLimits limits) {
        return new SimpleHostBindings(new JsonObject(), null, null, limits);
    }

    private static JsonObject document(double value) {
        final var document = new JsonObject();
        document.add("price", new JsonNumber(value));
        return document;
    }

    private ScriptCallable open(String source) {
        return simpleJs.openCallable(source, host());
    }

    // openCallable throws before it returns, so there is never a ScriptCallable to close here. The
    // suppression lives on this one helper rather than on every test that asserts a rejected source.
    @SuppressWarnings("resource")
    private ScriptCallableException failureOf(String source) {
        return assertThrows(ScriptCallableException.class, () -> open(source));
    }

    @Test
    public void test_calls_exported_default_per_document() {
        try (var callable = open("export default (doc) => doc.price * 2;")) {
            assertEquals(10d, callable.apply(document(5)).asJsonNumber().getValue().doubleValue());
            assertEquals(14d, callable.apply(document(7)).asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_calls_top_level_returned_function() {
        try (var callable = open("return (doc) => doc.price + 1;")) {
            assertEquals(4d, callable.apply(document(3)).asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_two_argument_fold_callable() {
        try (var callable = open("export default (acc, doc) => (acc ?? 0) + doc.price;")) {
            var accumulator = callable.apply(new org.techhouse.ejson.elements.JsonNull(), document(2));
            accumulator = callable.apply(accumulator, document(3));
            assertEquals(5d, accumulator.asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_rejects_non_callable() {
        final var error = failureOf("export default 42;");
        assertEquals("TypeError", error.getErrorName());
    }

    @Test
    public void test_rejects_missing_export() {
        final var error = failureOf("const x = 1;");
        assertEquals("TypeError", error.getErrorName());
    }

    @Test
    public void test_rejects_async_function() {
        final var error = failureOf("export default async (doc) => doc.price;");
        assertEquals("TypeError", error.getErrorName());
    }

    @Test
    public void test_rejects_generator_function() {
        final var error = failureOf("export default function* (doc) { yield doc.price; };");
        assertEquals("TypeError", error.getErrorName());
    }

    // The plan's central invariant: the budget belongs to the pipeline, not to one document.
    @Test
    public void test_shares_one_budget_across_calls() {
        final var limits = new ResourceLimits(3000, -1, -1);
        try (var callable = simpleJs.openCallable(
                "export default (doc) => { let n = 0;" + " for (let i = 0; i < 200; i++) { n += i; } return n; };",
                host(limits))) {
            var succeeded = 0;
            try {
                for (var i = 0; i < 1000; i++) {
                    callable.apply(document(i));
                    succeeded++;
                }
                fail("expected the shared instruction budget to abort a later call");
            } catch (ScriptCallableException error) {
                assertEquals("ScriptLimitError", error.getErrorName());
            }
            assertTrue(succeeded > 0, "the first calls must succeed, so the budget is shared and not per-call");
            assertTrue(succeeded < 1000);
        }
    }

    @Test
    public void test_deadline_spans_all_calls() {
        final var limits = new ResourceLimits(-1, 50, -1);
        try (var callable = simpleJs.openCallable(
                "export default (doc) => { let n = 0; for (let i = 0; i < 20000; i++) { n += i; } return n; };",
                host(limits))) {
            final var error = assertThrows(ScriptCallableException.class, () -> {
                for (var i = 0; i < 100000; i++) {
                    callable.apply(document(i));
                }
            });
            assertEquals("ScriptTimeoutError", error.getErrorName());
        }
    }

    // Without the per-document release, the document count alone would exhaust the memory budget.
    @Test
    public void test_memory_budget_is_released_between_documents() {
        final var limits = new ResourceLimits(-1, -1, -1, 64L * 1024);
        try (var callable = simpleJs.openCallable("export default (doc) => doc.price;", host(limits))) {
            for (var i = 0; i < 10000; i++) {
                assertEquals(i, callable.apply(document(i)).asJsonNumber().getValue().doubleValue());
            }
        }
    }

    @Test
    public void test_db_import_throws() {
        final var error = failureOf("import db from 'db'; export default (doc) => db;");
        assertNotNull(error.getMessage());
    }

    // fetch answers with a rejected promise rather than throwing, so the rejection is observed on the
    // next call - after the microtask the previous call queued has drained.
    @Test
    public void test_fetch_is_unavailable() {
        try (var callable = open("let seen = 'pending';"
                + " export default (doc) => { fetch('http://x').catch(e => { seen = e.name; }); return seen; };")) {
            callable.apply(document(1));
            assertEquals("TypeError", callable.apply(document(1)).asJsonString().getValue());
        }
    }

    @Test
    public void test_procedure_import_is_unresolvable() {
        final var error = failureOf("import p from 'procedures/x'; export default (doc) => p;");
        assertTrue(error.getMessage().contains("Cannot find module"), error.getMessage());
    }

    @Test
    public void test_import_text_is_disabled() {
        try (var callable = open("import s from 'script';"
                + " export default (doc) => { try { s.importText('export default 1;'); return 'reached';"
                + " } catch (e) { return e.name + ': ' + e.message; } };")) {
            final var value = callable.apply(document(1)).asJsonString().getValue();
            assertNotEquals("reached", value);
        }
    }

    @Test
    public void test_promise_resolved_inside_a_call_is_drained() {
        try (var callable = open(
                "let seen = 0; Promise.resolve().then(() => { seen = 1; });" + " export default (doc) => seen;")) {
            assertEquals(1d, callable.apply(document(1)).asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_close_is_idempotent() {
        final var callable = open("export default (doc) => doc.price;");
        callable.close();
        assertDoesNotThrow(callable::close);
    }

    @Test
    public void test_use_after_close_throws() {
        final var callable = open("export default (doc) => doc.price;");
        callable.close();
        assertThrows(RuntimeException.class, () -> callable.apply(document(1)));
    }

    @Test
    public void test_thrown_error_surfaces_with_its_name() {
        try (var callable = open("export default (doc) => { throw new RangeError('nope'); };")) {
            final var error = assertThrows(ScriptCallableException.class, () -> callable.apply(document(1)));
            assertEquals("RangeError", error.getErrorName());
            assertEquals("nope", error.getMessage());
        }
    }

    @Test
    public void test_syntax_error_surfaces_as_syntax_error() {
        final var error = failureOf("export default (doc) => {");
        assertEquals("SyntaxError", error.getErrorName());
    }

    @Test
    public void test_custom_types_cross_the_boundary() {
        try (var callable = open("export default (doc) => Geo.from({ lat: 1, lng: 2 });")) {
            assertTrue(callable.apply(document(1)).isJsonCustom());
        }
    }

    // undefined is the script declining to produce a value, and the MAP operator reads that as
    // "leave the field alone" - so it must stay distinguishable from a JSON null.
    @Test
    public void test_undefined_result_is_null_reference() {
        try (var callable = open("export default (doc) => undefined;")) {
            assertNull(callable.apply(document(1)));
        }
    }

    @Test
    public void test_arguments_are_the_document() {
        try (var callable = open("export default (doc) => JSON.stringify(doc);")) {
            final var document = new JsonObject();
            document.add("name", new JsonString("alice"));
            assertEquals("{\"name\":\"alice\"}", callable.apply(document).asJsonString().getValue());
        }
    }

    @Test
    public void test_compiled_script_overload_reuses_the_program() {
        final var compiled = simpleJs.compile("export default (doc) => doc.price;", false);
        try (var first = simpleJs.openCallable(compiled, host());
                var second = simpleJs.openCallable(compiled, host())) {
            assertEquals(1d, first.apply(document(1)).asJsonNumber().getValue().doubleValue());
            assertEquals(2d, second.apply(document(2)).asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_state_is_kept_between_calls_of_one_callable() {
        try (var callable = open("let seen = 0; export default (doc) => ++seen;")) {
            assertEquals(1d, callable.apply(document(1)).asJsonNumber().getValue().doubleValue());
            assertEquals(2d, callable.apply(document(1)).asJsonNumber().getValue().doubleValue());
        }
    }

    @Test
    public void test_run_still_works_after_the_shared_error_mapping_refactor() {
        final var result = simpleJs.run("return 1 + 1;", host());
        assertFalse(result.isError());
        assertEquals(2d, result.getValue().asJsonNumber().getValue().doubleValue());
        final var failure = simpleJs.run("throw new TypeError('x');", host());
        assertTrue(failure.isError());
        assertEquals("TypeError", failure.getErrorName());
    }

    @Test
    public void test_native_function_export_is_accepted() {
        try (var callable = open("export default String;")) {
            assertEquals("[object Object]", callable.apply(new JsonObject()).asJsonString().getValue());
        }
    }

    // The argument is an EJsonInterop.fromEjson conversion, not the host's own object, so a FILTER
    // predicate cannot corrupt a cached document by mutating what it is handed.
    @Test
    public void test_script_cannot_write_through_to_the_host_document() {
        try (var callable = open("export default (doc) => { doc.mutated = true; return doc.mutated; };")) {
            final var document = document(1);
            assertTrue(callable.apply(document).asJsonBoolean().getValue());
            assertNull(document.get("mutated"), "the script must not write through to the host document");
        }
    }

    @Test
    public void test_arguments_list_size_is_respected() {
        try (var callable = open("export default (...args) => args.length;")) {
            assertEquals(1d, callable.apply(document(1)).asJsonNumber().getValue().doubleValue());
            assertEquals(2d, callable.apply(new org.techhouse.ejson.elements.JsonNull(), document(1)).asJsonNumber()
                    .getValue().doubleValue());
        }
    }

    @Test
    public void test_open_failure_does_not_leak_a_session() {
        assertEquals("Error", failureOf("throw new Error('boom');").getErrorName());
        try (var callable = open("export default (doc) => 1;")) {
            assertEquals(1d, callable.apply(new JsonObject()).asJsonNumber().getValue().doubleValue());
        }
    }

}
