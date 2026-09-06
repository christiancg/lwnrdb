package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.unit.simplejs.host.FakeDatabaseAccess;

public class DynamicImportProgramTest {
    private final SimpleJs engine = new SimpleJs();

    private JsonObject args() {
        final var args = new JsonObject();
        args.add("name", new JsonString("named"));
        return args;
    }

    private ScriptResult run(String source) {
        return engine.run(source, new SimpleHostBindings(args(), null, null, ResourceLimits.unlimited()));
    }

    // Dynamic import of the args module resolves to a namespace exposing its members
    @Test
    public void test_dynamic_import_args_namespace() {
        final var result = run("const m = await import('args'); return m.name;");
        assertFalse(result.isError());
        assertEquals("named", result.getValue().asJsonString().getValue());
    }

    // The namespace also exposes a default binding mirroring the default import
    @Test
    public void test_dynamic_import_default_binding() {
        final var result = run("const m = await import('args'); return m.default.name;");
        assertEquals("named", result.getValue().asJsonString().getValue());
    }

    // Dynamic import of the db module resolves through DatabaseAccess
    @Test
    public void test_dynamic_import_db() {
        final var db = new FakeDatabaseAccess();
        final var stored = new JsonObject();
        stored.add("_id", new JsonString("u1"));
        db.nextFindResult = stored;
        final var source = "const m = await import('db'); return m.findById('mydb', 'users', 'u1')._id;";
        final var result = engine.run(source,
                new SimpleHostBindings(new JsonObject(), db, null, ResourceLimits.unlimited()));
        assertEquals("u1", result.getValue().asJsonString().getValue());
    }

    // An unknown specifier rejects with a catchable module-not-found error
    @Test
    public void test_dynamic_import_unknown_rejects() {
        final var source = "try { await import('nope'); return 'no'; } catch (e) { return e.message; }";
        final var result = run(source);
        assertEquals("Cannot find module 'nope'", result.getValue().asJsonString().getValue());
    }

    // import.meta.url is defined
    @Test
    public void test_import_meta_url() {
        assertEquals("simplejs:main", run("return import.meta.url;").getValue().asJsonString().getValue());
    }

    // A computed specifier expression is coerced to a string
    @Test
    public void test_dynamic_import_computed_specifier() {
        final var result = run("const name = 'ar' + 'gs'; const m = await import(name); return m.name;");
        assertEquals("named", result.getValue().asJsonString().getValue());
    }

    // A dynamic import at statement position parses as an expression statement, not a declaration
    @Test
    public void test_dynamic_import_statement_position() {
        final var result = run("import('args'); return 'ok';");
        assertEquals("ok", result.getValue().asJsonString().getValue());
    }

    // import.meta at statement position also parses as an expression
    @Test
    public void test_import_meta_statement_position() {
        final var result = run("import.meta; return 'ok';");
        assertEquals("ok", result.getValue().asJsonString().getValue());
    }

    // An unknown meta property is a syntax error
    @Test
    public void test_unknown_meta_property_is_syntax_error() {
        final var result = run("return import.foo;");
        assertTrue(result.isError());
        assertEquals("SyntaxError", result.getErrorName());
    }
}
