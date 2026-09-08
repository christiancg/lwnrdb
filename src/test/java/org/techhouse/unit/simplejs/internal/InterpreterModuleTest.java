package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.unit.simplejs.host.FakeDatabaseAccess;

public class InterpreterModuleTest {
    private final SimpleJs engine = new SimpleJs();

    private JsonObject args() {
        final var args = new JsonObject();
        args.add("0", new JsonString("positional"));
        args.add("name", new JsonString("named"));
        return args;
    }

    private ScriptResult runWithArgs(String source) {
        return engine.run(source, new SimpleHostBindings(args(), null, null, ResourceLimits.unlimited()));
    }

    private ScriptResult runWithDb(String source, FakeDatabaseAccess db) {
        return engine.run(source, new SimpleHostBindings(new JsonObject(), db, null, ResourceLimits.unlimited()));
    }

    // A default import of "args" binds the whole args object; positional access works
    @Test
    public void test_import_args_default_positional() {
        final var result = runWithArgs("import args from 'args'; return args[0];");
        assertEquals("positional", result.getValue().asJsonString().getValue());
    }

    // Named property access via dot and bracket both resolve
    @Test
    public void test_import_args_named() {
        assertEquals("named",
                runWithArgs("import args from 'args'; return args.name;").getValue().asJsonString().getValue());
        assertEquals("named",
                runWithArgs("import args from 'args'; return args['name'];").getValue().asJsonString().getValue());
    }

    // A named import pulls a single property out of the args module
    @Test
    public void test_import_named_specifier() {
        final var result = runWithArgs("import { name } from 'args'; return name;");
        assertEquals("named", result.getValue().asJsonString().getValue());
    }

    // A namespace import binds the whole args object
    @Test
    public void test_import_namespace() {
        final var result = runWithArgs("import * as a from 'args'; return a.name;");
        assertEquals("named", result.getValue().asJsonString().getValue());
    }

    // Importing an unknown module throws a catchable module-not-found error
    @Test
    public void test_unknown_module() {
        final var result = runWithArgs("import x from 'unknown';");
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("Cannot find module 'unknown'"));
    }

    // Import attributes are parsed and ignored
    @Test
    public void test_import_attributes_noop() {
        final var result = runWithArgs("import args from 'args' with { type: 'json' }; return args[0];");
        assertEquals("positional", result.getValue().asJsonString().getValue());
    }

    // The db module dispatches through DatabaseAccess and returns the found document
    @Test
    public void test_import_db_find() {
        final var db = new FakeDatabaseAccess();
        final var stored = new JsonObject();
        stored.add("_id", new JsonString("u1"));
        db.nextFindResult = stored;
        final var result = runWithDb("import db from 'db'; return db.findById('mydb', 'users', 'u1');", db);
        assertFalse(result.isError());
        assertEquals("u1", result.getValue().asJsonObject().get("_id").asJsonString().getValue());
    }

    // A denied db call surfaces as an error result the script did not catch
    @Test
    public void test_import_db_denied() {
        final var db = new FakeDatabaseAccess();
        db.denyMessage = "action is forbidden, no permissions";
        final var result = runWithDb("import db from 'db'; return db.save('mydb', 'users', {});", db);
        assertTrue(result.isError());
        assertEquals("action is forbidden, no permissions", result.getErrorMessage());
    }

    // A script can catch a db rejection with try/catch
    @Test
    public void test_import_db_denied_catchable() {
        final var db = new FakeDatabaseAccess();
        db.denyMessage = "schema violation";
        final var source = "import db from 'db';"
                + " try { db.save('mydb', 'users', {}); return 'no'; } catch (e) { return e.message; }";
        final var result = runWithDb(source, db);
        assertEquals("schema violation", result.getValue().asJsonString().getValue());
    }

    // Requesting db access when the host provides none throws
    @Test
    public void test_import_db_unavailable() {
        final var result = runWithArgs("import db from 'db'; return db.findById('a', 'b', 'c');");
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("Database access is not available"));
    }

    // A re-export copies the source module's members into the named exports
    @Test
    public void test_export_all() {
        final var result = runWithArgs("export * from 'args';");
        assertEquals("named", result.getValue().asJsonObject().get("name").asJsonString().getValue());
    }

    // An exported function declaration is callable and recorded as a named export
    @Test
    public void test_export_function_declaration() {
        final var result = engine.run("export function twice(x) { return x * 2; } return twice(21);",
                SimpleHostBindings.empty());
        assertEquals(42, result.getValue().asJsonNumber().asInteger());
    }

    // An exported class declaration is recorded as a named export under its own name
    @Test
    public void test_export_class_declaration() {
        final var outcome = Interpreter.run("export class Box { static make() { return 5; } }",
                SimpleHostBindings.empty());
        assertInstanceOf(JsClass.class, outcome.namedExports().get("Box"));
        assertEquals("Box", ((JsClass) outcome.namedExports().get("Box")).getName());
    }

    // `export default function foo() {}` parses as a function *expression* in default-export
    // position, so this exercises evalExportDefault's generic Expression fallback
    @Test
    public void test_export_default_function_expression() {
        final var outcome = Interpreter.run("export default function foo() { return 1; }", SimpleHostBindings.empty());
        assertInstanceOf(JsFunction.class, outcome.exportDefault());
        assertEquals("foo", ((JsFunction) outcome.exportDefault()).getName());
    }

    // `export default class Foo {}` parses as a class *expression* in default-export position, so
    // this exercises evalExportDefault's generic Expression fallback
    @Test
    public void test_export_default_class_expression() {
        final var outcome = Interpreter.run("export default class Foo { static make() { return 2; } }",
                SimpleHostBindings.empty());
        assertInstanceOf(JsClass.class, outcome.exportDefault());
        final var cls = (JsClass) outcome.exportDefault();
        assertEquals("Foo", cls.getName());
        assertInstanceOf(JsFunction.class, cls.findStaticMethod("make"));
    }

    // `export { a, b as c }` resolves each local binding and renames as requested
    @Test
    public void test_export_named_specifier_list_with_rename() {
        final var outcome = Interpreter.run("let a = 1; let b = 2; export { a, b as c };", SimpleHostBindings.empty());
        assertEquals(1, ((JsNumber) outcome.namedExports().get("a")).getValue());
        assertEquals(2, ((JsNumber) outcome.namedExports().get("c")).getValue());
        assertFalse(outcome.namedExports().containsKey("b"));
    }

    // `export * as ns from "..."` binds the whole re-exported module namespace under one name
    @Test
    public void test_export_all_as_namespace() {
        final var outcome = Interpreter.run("export * as ns from 'args';",
                new SimpleHostBindings(args(), null, null, ResourceLimits.unlimited()));
        final var namespace = (JsObject) outcome.namedExports().get("ns");
        assertEquals("named", ((JsString) namespace.get("name")).getValue());
    }

    // A string-literal name is a valid import specifier ("imported name" position)
    @Test
    public void test_import_specifier_string_literal_name() {
        final var result = runWithArgs("import { \"name\" as localName } from 'args'; return localName;");
        assertEquals("named", result.getValue().asJsonString().getValue());
    }

    // A string-literal name is a valid export specifier ("exported name" position)
    @Test
    public void test_export_specifier_string_literal_name() {
        final var outcome = Interpreter.run("let a = 1; export { a as \"exported name\" };",
                SimpleHostBindings.empty());
        assertEquals(1, ((JsNumber) outcome.namedExports().get("exported name")).getValue());
    }
}
