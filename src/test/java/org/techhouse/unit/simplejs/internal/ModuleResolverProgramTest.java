package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("main", text(run(resolver, "import lib from 'lib'; return lib.default;")));
        assertEquals(3, number(run(resolver, "import { f } from 'lib'; return f();")));
        assertEquals(3, number(run(resolver, "import * as ns from 'lib'; return ns.f();")));
        assertEquals("main", text(run(resolver, "const ns = await import('lib'); return ns.default.default;")));
    }

    // The referrer is passed to the resolver
    @Test
    public void test_resolver_receives_referrer() {
        final var resolver = resolverOf(Map.of("lib", new ResolvedModule("pkg:lib", "export default 1;")));
        run(resolver, "import lib from 'lib'; return lib.default;");
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
                store.default();
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
        final var resolver = resolverOf(Map.of("top",
                new ResolvedModule("pkg:top", "import base from 'base'; export default base.default + 1;"), "base",
                new ResolvedModule("pkg:base", "export default 41;")));
        assertEquals(42, number(run(resolver, "import top from 'top'; return top.default;")));
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

    // The built-in specifiers still win over the host resolver
    @Test
    public void test_builtins_are_not_routed_to_the_resolver() {
        final var resolver = resolverOf(Map.of("args", new ResolvedModule("pkg:args", "export default 'hijacked';"),
                "db", new ResolvedModule("pkg:db", "export default 'hijacked';")));
        final var result = run(resolver, "import args from 'args'; return typeof args;");
        assertEquals("object", text(result));
        assertFalse(resolved.contains("args<-main"));
    }
}
