package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ModuleResolver;
import org.techhouse.simplejs.host.ResolvedModule;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.unit.simplejs.host.FakeDatabaseAccess;
import org.techhouse.unit.simplejs.host.ModuleHostBindings;

public class ModuleResolverProgramTest {
    private final SimpleJs engine = new SimpleJs();
    private final List<String> resolved = new ArrayList<>();

    private ModuleResolver resolverOf(Map<String, ResolvedModule> modules) {
        return (specifier, referrer) -> {
            resolved.add(specifier + "<-" + referrer);
            return modules.get(specifier);
        };
    }

    private ScriptResult run(ModuleResolver resolver, String source) {
        return engine.run(source, ModuleHostBindings.of(resolver, ResourceLimits.unlimited()));
    }

    private static String text(ScriptResult result) {
        return result.getValue().asJsonString().getValue();
    }

    private static double number(ScriptResult result) {
        return result.getValue().asJsonNumber().getValue().doubleValue();
    }

    // A bare specifier the host resolves is imported like any other module
    @Test
    public void test_bare_specifier_resolved_by_host() {
        final var resolver = resolverOf(Map.of("lib",
                new ResolvedModule("pkg:lib", "export function f(){ return 3; } export default 'main';")));
        assertEquals("main", text(run(resolver, "import lib from 'lib'; return lib;")));
        assertEquals(3, number(run(resolver, "import { f } from 'lib'; return f();")));
        assertEquals(3, number(run(resolver, "import * as ns from 'lib'; return ns.f();")));
        assertEquals("main", text(run(resolver, "import * as ns from 'lib'; return ns.default;")));
        assertEquals("main", text(run(resolver, "const ns = await import('lib'); return ns.default;")));
    }

    // The referrer is passed to the resolver
    @Test
    public void test_resolver_receives_referrer() {
        final var resolver = resolverOf(Map.of("lib", new ResolvedModule("pkg:lib", "export default 1;")));
        run(resolver, "import lib from 'lib'; return lib;");
        assertEquals(List.of("lib<-main"), resolved);
    }

    // Two specifiers naming the same module id share one evaluation and one namespace
    @Test
    public void test_two_specifiers_same_module_id_share_instance() {
        final var database = new FakeDatabaseAccess();
        final var source = "import db from 'db'; db.delete('d', 'c', 'once'); export default {};";
        final var resolver = resolverOf(Map.of("lib", new ResolvedModule("pkg:lib", source), "lib/index.js",
                new ResolvedModule("pkg:lib", source)));
        final var result = engine.run("""
                import a from 'lib';
                import b from 'lib/index.js';
                return a === b;
                """, ModuleHostBindings.of(resolver, ResourceLimits.unlimited(), database));
        assertTrue(result.getValue().asJsonBoolean().getValue());
        assertEquals(1, database.calls.stream().filter(call -> call.equals("delete:d/c/once")).count());
    }

    // With no resolver installed an unknown specifier keeps the standalone error
    @Test
    public void test_no_resolver_keeps_cannot_find_module() {
        final var result = engine.run("""
                try {
                    await import('lib');
                } catch (e) {
                    return e.message;
                }
                return 'resolved';
                """, new SimpleHostBindings(new JsonObject(), null, null, ResourceLimits.unlimited()));
        assertEquals("Cannot find module 'lib'", text(result));
    }

    // A resolver that does not claim the specifier produces the same error
    @Test
    public void test_resolver_returning_null_is_cannot_find_module() {
        final var resolver = resolverOf(Map.of());
        final var result = run(resolver, """
                try {
                    await import('nope');
                } catch (e) {
                    return e.message;
                }
                return 'resolved';
                """);
        assertEquals("Cannot find module 'nope'", text(result));
    }

    // A resolved module reaches the same db binding as its importer
    @Test
    public void test_resolver_module_can_import_db() {
        final var database = new FakeDatabaseAccess();
        final var resolver = resolverOf(Map.of("store",
                new ResolvedModule("pkg:store", "import db from 'db'; export default () => db.listDatabases();")));
        final var result = engine.run("""
                import store from 'store';
                store();
                return 'ok';
                """, ModuleHostBindings.of(resolver, ResourceLimits.unlimited(), database));
        assertEquals("ok", text(result));
        assertTrue(database.calls.contains("listDatabases"));
    }

    // A resolved module that is itself malformed raises a catchable SyntaxError
    @Test
    public void test_resolved_module_syntax_error_is_catchable() {
        final var resolver = resolverOf(Map.of("bad", new ResolvedModule("pkg:bad", "let =")));
        final var result = run(resolver, """
                try {
                    await import('bad');
                } catch (e) {
                    return e.name;
                }
                return 'parsed';
                """);
        assertEquals("SyntaxError", text(result));
    }

    // A resolved module may import another resolved module
    @Test
    public void test_resolved_module_can_import_another() {
        final var resolver = resolverOf(
                Map.of("top", new ResolvedModule("pkg:top", "import base from 'base'; export default base + 1;"),
                        "base", new ResolvedModule("pkg:base", "export default 41;")));
        assertEquals(42, number(run(resolver, "import top from 'top'; return top;")));
    }

    // Two resolved modules importing each other are detected as a cycle
    @Test
    public void test_resolved_module_cycle_detected() {
        final var resolver = resolverOf(Map.of("a", new ResolvedModule("pkg:a", "import b from 'b'; export default 1;"),
                "b", new ResolvedModule("pkg:b", "import a from 'a'; export default 2;")));
        final var result = run(resolver, """
                try {
                    await import('a');
                } catch (e) {
                    return e.message;
                }
                return 'no-cycle';
                """);
        assertEquals("Circular import of module 'pkg:a'", text(result));
    }

    // A named re-export takes its bindings from the named module, not from the local scope. A re-export
    // introduces no local binding, so the assertions read the module's exports - which is what the result
    // contract returns when a script has no top-level return.
    @Test
    public void test_named_reexport_resolves_its_source() {
        final var resolver = resolverOf(
                Map.of("lib", new ResolvedModule("pkg:lib", "export const RATE = 0.21; export default 'main';")));
        final var exports = run(resolver, "export { RATE } from 'lib';").getValue().asJsonObject();
        assertEquals(0.21, exports.get("RATE").asJsonNumber().getValue().doubleValue(), 0.0);

        final var renamed = run(resolver, "export { RATE as rate } from 'lib';").getValue().asJsonObject();
        assertEquals(0.21, renamed.get("rate").asJsonNumber().getValue().doubleValue(), 0.0);

        final var defaulted = run(resolver, "export { default as main } from 'lib';").getValue().asJsonObject();
        assertEquals("main", defaulted.get("main").asJsonString().getValue());

        // The plain import form was never broken; kept here so the two stay in step.
        assertEquals(0.21, number(run(resolver, "import { RATE } from 'lib';\nreturn RATE;")), 0.0);
    }

    // The bug this replaced: a local of the same name was re-exported instead of the module's binding
    @Test
    public void test_named_reexport_is_not_shadowed_by_a_local() {
        final var resolver = resolverOf(Map.of("lib", new ResolvedModule("pkg:lib", "export const RATE = 0.21;")));
        final var exports = run(resolver, "const RATE = 999;\nexport { RATE } from 'lib';").getValue().asJsonObject();
        assertEquals(0.21, exports.get("RATE").asJsonNumber().getValue().doubleValue(), 0.0,
                "the module's binding must win over a local of the same name");
    }

    // A sourceless `export { x }` still reads the local scope
    @Test
    public void test_sourceless_named_export_still_reads_the_local_scope() {
        final var resolver = resolverOf(Map.of());
        final var exports = run(resolver, "const value = 7;\nexport { value };").getValue().asJsonObject();
        assertEquals(7, exports.get("value").asJsonNumber().getValue().intValue());
    }

    // Re-exporting from a module that cannot be resolved names the module, not a missing local
    @Test
    public void test_named_reexport_from_a_missing_module_reports_the_module() {
        final var result = run(resolverOf(Map.of()), "export { x } from 'nope';");
        assertEquals("Cannot find module 'nope'", result.getErrorMessage());
    }

    // `export *` carries the named exports only - `default` is not one of them
    @Test
    public void test_export_star_does_not_reexport_default() {
        final var resolver = resolverOf(
                Map.of("lib", new ResolvedModule("pkg:lib", "export const a = 1; export default 'skipped';")));
        final var exports = run(resolver, "export * from 'lib';").getValue().asJsonObject();
        assertTrue(exports.has("a"), exports.toString());
        assertFalse(exports.has("default"), "default must not arrive as a named export: " + exports);
    }

    // A re-export may name a built-in, and `default` on one means the built-in itself. The db module's
    // members are all functions, which the host boundary cannot represent, so the assertion is that the
    // specifier resolved at all - before the fix these were a ReferenceError about a missing local.
    @Test
    public void test_reexport_from_a_builtin() {
        final var database = new FakeDatabaseAccess();
        final var host = ModuleHostBindings.of(resolverOf(Map.of()), ResourceLimits.unlimited(), database);
        final var member = engine.run("export { findById } from 'db';", host);
        assertNull(member.getErrorName(), member.getErrorMessage());
        final var defaulted = engine.run("export { default as database } from 'db';", host);
        assertNull(defaulted.getErrorName(), defaulted.getErrorMessage());
    }

    // The built-in specifiers still win over the host resolver
    @Test
    public void test_builtins_are_not_routed_to_the_resolver() {
        final var resolver = resolverOf(Map.of("args", new ResolvedModule("pkg:args", "export default 'hijacked';"),
                "db", new ResolvedModule("pkg:db", "export default 'hijacked';")));
        final var result = run(resolver, "import args from 'args'; return typeof args;");
        assertEquals("object", text(result));
        assertFalse(resolved.contains("args<-main"));
    }

    // A built-in is the object a default import binds directly - it has no `default` member to unwrap -
    // while a resolved module's default import binds its default export. Losing the distinction is what
    // would make `import db from 'db'` bind a wrapper instead of the db object.
    @Test
    public void test_builtin_default_import_binds_the_builtin_itself() {
        final var database = new FakeDatabaseAccess();
        final var host = ModuleHostBindings.of(resolverOf(Map.of()), ResourceLimits.unlimited(), database);
        assertEquals("function", text(engine.run("import db from 'db'; return typeof db.findById;", host)));
        assertEquals("function", text(engine.run("import * as ns from 'db'; return typeof ns.findById;", host)));
        // The static and dynamic namespace forms agree: a built-in is wrapped either way, so `default`
        // is present and is the built-in itself.
        assertEquals("function",
                text(engine.run("import * as ns from 'db'; return typeof ns.default.findById;", host)));
        assertEquals("function",
                text(engine.run("const ns = await import('db'); return typeof ns.default.findById;", host)));
    }

    // A resolved module with no default export binds undefined, not the namespace object
    @Test
    public void test_resolved_module_without_default_binds_undefined() {
        final var resolver = resolverOf(Map.of("named", new ResolvedModule("pkg:named", "export const a = 1;")));
        assertEquals("undefined", text(run(resolver, "import whole from 'named'; return typeof whole;")));
        assertEquals("object", text(run(resolver, "import * as ns from 'named'; return typeof ns;")));
    }
}
