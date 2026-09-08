package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.unit.simplejs.host.FakeDatabaseAccess;

public class ImportTextProgramTest {
    private final SimpleJs engine = new SimpleJs();

    private static ResourceLimits enabled() {
        return limits(-1, ResourceLimits.DEFAULT_MAX_MODULE_DEPTH);
    }

    private static ResourceLimits limits(long instructionBudget, int maxModuleDepth) {
        return new ResourceLimits(instructionBudget, -1, -1, true, false, List.of(), -1, -1, false, true,
                maxModuleDepth);
    }

    private ScriptResult run(String source) {
        return engine.run(source, new SimpleHostBindings(new JsonObject(), null, null, enabled()));
    }

    private ScriptResult run(String source, ResourceLimits limits) {
        return engine.run(source, new SimpleHostBindings(new JsonObject(), null, null, limits));
    }

    private ScriptResult runWithDb(String source, FakeDatabaseAccess database) {
        return engine.run(source, new SimpleHostBindings(new JsonObject(), database, null, enabled()));
    }

    private static String text(ScriptResult result) {
        return result.getValue().asJsonString().getValue();
    }

    private static double number(ScriptResult result) {
        return result.getValue().asJsonNumber().getValue().doubleValue();
    }

    // importText returns the imported module's default export
    @Test
    public void test_import_text_default_export() {
        final var result = run("""
                import script from 'script';
                return script.importText('export default 42;').default;
                """);
        assertEquals(42, number(result));
    }

    // Named exports land on the returned namespace and stay callable
    @Test
    public void test_import_text_named_export() {
        final var result = run("""
                import script from 'script';
                const m = script.importText("export function greet(n) { return 'hi ' + n; }");
                return m.greet('world');
                """);
        assertEquals("hi world", text(result));
    }

    // The imported module shares the importer's realm, so cross-boundary prototypes still match
    @Test
    public void test_import_text_shares_realm_intrinsics() {
        final var result = run("""
                import script from 'script';
                const m = script.importText('export default [1, 2];');
                let caught = null;
                try {
                    script.importText("throw new TypeError('boom');", 'thrower');
                } catch (e) {
                    caught = e;
                }
                return (m.default instanceof Array) + '|' + (caught instanceof TypeError) + '|' + caught.message;
                """);
        assertEquals("true|true|boom", text(result));
    }

    // A promise created by imported code settles on the importer's event loop
    @Test
    public void test_import_text_promise_settles_in_importer() {
        final var result = run("""
                import script from 'script';
                const m = script.importText(
                    "export function go(){ return new Promise(r => setTimeout(() => r('fired'), 0)); }");
                return await m.go();
                """);
        assertEquals("fired", text(result));
    }

    // An async export resolves through the shared loop too
    @Test
    public void test_import_text_async_export_resolves() {
        final var result = run("""
                import script from 'script';
                const m = script.importText('export async function go(){ return 5; }');
                return await m.go();
                """);
        assertEquals(5, number(result));
    }

    // A top-level await inside the imported module parks the importer's coroutine
    @Test
    public void test_import_text_top_level_await() {
        final var result = run("""
                import script from 'script';
                return script.importText('export default await Promise.resolve(9);').default;
                """);
        assertEquals(9, number(result));
    }

    // The imported module runs on the importer's thread, so an open transaction stays usable
    @Test
    public void test_import_text_inside_transaction() {
        final var database = new FakeDatabaseAccess();
        final var result = runWithDb("""
                import script from 'script';
                import db from 'db';
                db.transaction(() => {
                    script.importText("import db from 'db'; db.save('d', 'c', { _id: 'x' });");
                });
                return 'ok';
                """, database);
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("ok", text(result));
        assertTrue(database.calls.contains("save:d/c"));
        assertTrue(database.calls.contains("commitTransaction"));
    }

    // The same text is one module: it evaluates once and both imports share the namespace
    @Test
    public void test_import_text_evaluated_once() {
        final var database = new FakeDatabaseAccess();
        final var result = runWithDb("""
                import script from 'script';
                const src = "import db from 'db'; db.delete('d', 'c', 'once'); export default 1;";
                const first = script.importText(src);
                const second = script.importText(src);
                return first === second;
                """, database);
        assertTrue(result.getValue().asJsonBoolean().getValue());
        assertEquals(1, database.calls.stream().filter(call -> call.equals("delete:d/c/once")).count());
    }

    // An explicit module id makes two different sources the same module
    @Test
    public void test_import_text_explicit_module_id_is_the_key() {
        final var result = run("""
                import script from 'script';
                const first = script.importText('export default 1;', 'shared');
                const second = script.importText('export default 2;', 'shared');
                return second.default;
                """);
        assertEquals(1, number(result));
    }

    // A module that throws stays failed and rethrows the original error without re-running
    @Test
    public void test_import_text_failure_is_sticky() {
        final var database = new FakeDatabaseAccess();
        final var result = runWithDb("""
                import script from 'script';
                const src = "import db from 'db'; db.delete('d', 'c', 'boom'); throw new Error('once');";
                const messages = [];
                for (let i = 0; i < 2; i++) {
                    try { script.importText(src, 'failing'); } catch (e) { messages.push(e.message); }
                }
                return messages.join(',');
                """, database);
        assertEquals("once,once", text(result));
        assertEquals(1, database.calls.stream().filter(call -> call.equals("delete:d/c/boom")).count());
    }

    // A module importing itself is detected as a cycle rather than recursing to the depth cap
    @Test
    public void test_import_text_cycle_detected() {
        final var result = run("""
                import script from 'script';
                const src = "import script from 'script'; script.importText('export default 1;', 'A');";
                try {
                    script.importText(src, 'A');
                } catch (e) {
                    return e.name + ':' + e.message;
                }
                return 'not-detected';
                """);
        assertEquals("Error:Circular import of module 'A'", text(result));
    }

    // A chain of distinct modules is bounded by maxModuleDepth, and the abort is not catchable
    @Test
    public void test_import_text_module_depth_exceeded() {
        final var result = run(
                """
                        import script from 'script';
                        globalThis.__n = 0;
                        globalThis.__src = "import script from 'script'; script.importText(globalThis.__src, 'm' + (globalThis.__n++));";
                        try {
                            script.importText(globalThis.__src, 'root');
                        } catch (e) {
                            return 'caught';
                        }
                        return 'no-abort';
                        """,
                limits(-1, 4));
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
        assertEquals("Script exceeded its maximum module nesting depth", result.getErrorMessage());
    }

    // Nested modules spend the importer's single instruction budget, including when they throw
    @Test
    public void test_import_text_shares_instruction_budget() {
        final var result = run("""
                import script from 'script';
                let n = 0;
                while (true) {
                    try { script.importText('while (true) {}', 'b' + (n++)); } catch (e) { }
                }
                """, limits(200_000, ResourceLimits.DEFAULT_MAX_MODULE_DEPTH));
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
        assertEquals("Script exceeded its instruction budget", result.getErrorMessage());
    }

    // The imported module sees the same args and the same database binding
    @Test
    public void test_import_text_sees_shared_args_and_db() {
        final var database = new FakeDatabaseAccess();
        database.nextFindResult = new JsonObject();
        database.nextFindResult.add("name", new JsonString("found"));
        final var args = new JsonObject();
        args.add("who", new JsonString("caller"));
        final var result = engine.run("""
                import script from 'script';
                const m = script.importText(
                    "import args from 'args'; import db from 'db';"
                    + "export default args.who + ':' + db.findById('d', 'c', '1').name;");
                return m.default;
                """, new SimpleHostBindings(args, database, null, enabled()));
        assertEquals("caller:found", text(result));
        assertTrue(database.calls.contains("findById:d/c/1"));
    }

    // A module's var declarations stay in the module scope, but globals remain visible to it
    @Test
    public void test_import_text_module_scope_is_private() {
        final var result = run("""
                import script from 'script';
                globalThis.shared = 'outer';
                const m = script.importText('var leaked = 1; export default shared;');
                return typeof globalThis.leaked + '|' + m.default;
                """);
        assertEquals("undefined|outer", text(result));
    }

    // The capability is off unless the host turns it on
    @Test
    public void test_import_text_disabled_by_default() {
        final var result = engine.run("""
                import script from 'script';
                try {
                    script.importText('export default 1;');
                } catch (e) {
                    return e.name + ':' + e.message;
                }
                return 'not-gated';
                """, new SimpleHostBindings(new JsonObject(), null, null, ResourceLimits.unlimited()));
        assertEquals("Error:Script text import is not available", text(result));
    }

    // The gate applies through dynamic import too
    @Test
    public void test_import_text_disabled_via_dynamic_import() {
        final var result = engine.run("""
                const ns = await import('script');
                try {
                    ns.default.importText('export default 1;');
                } catch (e) {
                    return e.message;
                }
                return 'not-gated';
                """, new SimpleHostBindings(new JsonObject(), null, null, ResourceLimits.unlimited()));
        assertEquals("Script text import is not available", text(result));
    }

    // Every static import form binds the script module
    @Test
    public void test_script_module_import_forms() {
        assertEquals(1, number(run("""
                import { importText } from 'script';
                return importText('export default 1;').default;
                """)));
        assertEquals(2, number(run("""
                import * as ns from 'script';
                return ns.importText('export default 2;').default;
                """)));
    }

    // A malformed source is a catchable SyntaxError, not an abort
    @Test
    public void test_import_text_syntax_error_is_catchable() {
        final var result = run("""
                import script from 'script';
                try {
                    script.importText('let =');
                } catch (e) {
                    return e.name;
                }
                return 'not-thrown';
                """);
        assertEquals("SyntaxError", text(result));
    }

    // An error thrown by the module body reaches the importer's own catch
    @Test
    public void test_import_text_thrown_error_is_catchable() {
        final var result = run("""
                import script from 'script';
                try {
                    script.importText("throw new Error('from module');");
                } catch (e) {
                    return e.message;
                }
                return 'not-thrown';
                """);
        assertEquals("from module", text(result));
    }

    // An empty source is a valid module with no exports
    @Test
    public void test_import_text_empty_source() {
        final var result = run("""
                import script from 'script';
                const m = script.importText('   ');
                return typeof m.default + '|' + Object.keys(m).length;
                """);
        assertEquals("undefined|1", text(result));
    }

    // A missing argument coerces to the string "undefined", which is a valid (empty) program
    @Test
    public void test_import_text_without_argument() {
        final var result = run("""
                import script from 'script';
                return typeof script.importText().default;
                """);
        assertEquals("undefined", text(result));
    }

    // A non-string source is coerced before parsing
    @Test
    public void test_import_text_coerces_source() {
        final var result = run("""
                import script from 'script';
                return typeof script.importText(123).default;
                """);
        assertEquals("undefined", text(result));
    }

    // A script imported by text can itself import a third script, transitively
    @Test
    public void test_import_text_nests_three_levels() {
        final var result = run("""
                import script from 'script';
                const leaf = "export default 1;";
                const middle = "import script from 'script';"
                    + "export default script.importText(globalThis.__leaf).default + 10;";
                globalThis.__leaf = leaf;
                globalThis.__middle = middle;
                const top = "import script from 'script';"
                    + "export default script.importText(globalThis.__middle).default + 100;";
                return script.importText(top).default;
                """);
        assertEquals(111, number(result));
    }

    // A diamond graph evaluates the shared leaf once and hands both branches the same namespace
    @Test
    public void test_import_text_diamond_evaluates_shared_module_once() {
        final var database = new FakeDatabaseAccess();
        final var result = runWithDb("""
                import script from 'script';
                globalThis.__leaf = "import db from 'db'; db.delete('d', 'c', 'leaf'); export default {};";
                const branch = "import script from 'script';"
                    + "export default script.importText(globalThis.__leaf, 'leaf');";
                globalThis.__branch = branch;
                const left = script.importText("import script from 'script';"
                    + "export default script.importText(globalThis.__branch, 'b').default;", 'left');
                const right = script.importText("import script from 'script';"
                    + "export default script.importText(globalThis.__branch, 'b').default;", 'right');
                return left.default === right.default;
                """, database);
        assertTrue(result.getValue().asJsonBoolean().getValue());
        assertEquals(1, database.calls.stream().filter(call -> call.equals("delete:d/c/leaf")).count());
    }

    // A nested module keeps the shared realm and event loop at depth
    @Test
    public void test_import_text_nested_module_shares_realm_and_loop() {
        final var result = run("""
                import script from 'script';
                globalThis.__leaf = "export async function go(){ return [1, 2]; }";
                const middle = "import script from 'script';"
                    + "export default script.importText(globalThis.__leaf);";
                const m = script.importText(middle);
                const value = await m.default.go();
                return (value instanceof Array) + '|' + value.length;
                """);
        assertEquals("true|2", text(result));
    }

    // A top-level return inside an imported module does not leak into the importer's contract
    @Test
    public void test_import_text_module_top_level_return_is_discarded() {
        final var result = run("""
                import script from 'script';
                const m = script.importText('return 99;');
                return typeof m.default;
                """);
        assertEquals("undefined", text(result));
    }
}
