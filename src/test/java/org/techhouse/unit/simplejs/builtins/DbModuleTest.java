package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.builtins.DbModule;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.unit.simplejs.host.FakeDatabaseAccess;

public class DbModuleTest {
    private static JsValue call(JsObject db, String method, JsValue... args) {
        final var fn = (JsNativeFunction) db.get(method);
        return fn.invoke(JsUndefined.getInstance(), List.of(args));
    }

    // findById passes the coerced db/coll/id through and converts the result to a JsValue
    @Test
    public void test_find_by_id() {
        final var fake = new FakeDatabaseAccess();
        final var stored = new JsonObject();
        stored.add("_id", new JsonString("x1"));
        fake.nextFindResult = stored;
        final var db = DbModule.create(fake, null);
        final var result = call(db, "findById", new JsString("mydb"), new JsString("users"), new JsString("x1"));
        assertInstanceOf(JsObject.class, result);
        assertEquals("x1", ((JsString) ((JsObject) result).get("_id")).getValue());
        assertEquals("findById:mydb/users/x1", fake.calls.getFirst());
    }

    // A null document from findById converts to JsNull
    @Test
    public void test_find_by_id_missing() {
        final var fake = new FakeDatabaseAccess();
        final var db = DbModule.create(fake, null);
        final var result = call(db, "findById", new JsString("d"), new JsString("c"), new JsString("nope"));
        assertInstanceOf(JsNull.class, result);
    }

    // aggregate forwards the pipeline array and returns an array of results
    @Test
    public void test_aggregate() {
        final var fake = new FakeDatabaseAccess();
        final var db = DbModule.create(fake, null);
        final var pipeline = new JsArray();
        pipeline.push(new JsObject());
        final var result = call(db, "aggregate", new JsString("d"), new JsString("c"), pipeline);
        assertInstanceOf(JsArray.class, result);
        assertEquals(1, ((JsArray) result).length());
        assertEquals("aggregate:d/c/1", fake.calls.getFirst());
    }

    // save forwards the document and returns the stored document
    @Test
    public void test_save() {
        final var fake = new FakeDatabaseAccess();
        final var db = DbModule.create(fake, null);
        final var document = new JsObject();
        document.set("_id", new JsString("s1"));
        final var result = call(db, "save", new JsString("d"), new JsString("c"), document);
        assertInstanceOf(JsObject.class, result);
        assertEquals("save:d/c", fake.calls.getFirst());
    }

    // delete returns undefined and records the call
    @Test
    public void test_delete() {
        final var fake = new FakeDatabaseAccess();
        final var db = DbModule.create(fake, null);
        final var result = call(db, "delete", new JsString("d"), new JsString("c"), new JsString("s1"));
        assertInstanceOf(JsUndefined.class, result);
        assertEquals("delete:d/c/s1", fake.calls.getFirst());
    }

    // listCollections and listDatabases return string arrays
    @Test
    public void test_list_operations() {
        final var fake = new FakeDatabaseAccess();
        final var db = DbModule.create(fake, null);
        final var collections = call(db, "listCollections", new JsString("d"));
        assertInstanceOf(JsArray.class, collections);
        assertEquals(2, ((JsArray) collections).length());
        final var databases = call(db, "listDatabases");
        assertEquals(1, ((JsArray) databases).length());
        assertTrue(fake.calls.contains("listDatabases"));
    }

    // A round-trip through EJsonInterop keeps a stored id intact
    @Test
    public void test_interop_roundtrip() {
        final var object = new JsObject();
        object.set("_id", new JsString("r1"));
        final var restored = (JsObject) EJsonInterop.fromEjson(EJsonInterop.toEjson(object));
        assertEquals("r1", ((JsString) restored.get("_id")).getValue());
    }

    // bulkSave forwards the whole batch and reports both id lists
    @Test
    public void test_bulk_save() {
        final var fake = new FakeDatabaseAccess();
        final var db = DbModule.create(fake, null);
        final var documents = new JsArray();
        documents.push(new JsObject());
        documents.push(new JsObject());
        final var result = (JsObject) call(db, "bulkSave", new JsString("d"), new JsString("c"), documents);
        assertEquals("bulkSave:d/c/2", fake.calls.getFirst());
        assertEquals(1, ((JsArray) result.get("inserted")).length());
        assertEquals(1, ((JsArray) result.get("updated")).length());
    }

    // bulkSave rejects anything that is not an array of documents
    @Test
    public void test_bulk_save_requires_documents() {
        final var db = DbModule.create(new FakeDatabaseAccess(), null);
        assertThrows(TypeErrorException.class,
                () -> call(db, "bulkSave", new JsString("d"), new JsString("c"), new JsString("nope")));
    }

    // Both new members appear on the module object, so `import * as db` picks them up automatically
    @Test
    public void test_module_exposes_new_members() {
        final var db = DbModule.create(new FakeDatabaseAccess(), null);
        assertInstanceOf(JsNativeFunction.class, db.get("bulkSave"));
        assertInstanceOf(JsNativeFunction.class, db.get("transaction"));
    }

    private static ScriptResult runScript(FakeDatabaseAccess fake, String source) {
        return new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), fake, null, ResourceLimits.unlimited()));
    }

    // A synchronous callback runs between begin and commit
    @Test
    public void test_transaction_commits() {
        final var fake = new FakeDatabaseAccess();
        final var result = runScript(fake, """
                import db from "db";
                return db.transaction(() => { db.delete("d", "c", "x"); return 7; });
                """);
        assertFalse(result.isError());
        assertEquals(7, result.getValue().asJsonNumber().asInteger());
        assertEquals(List.of("beginTransaction", "delete:d/c/x", "commitTransaction"), fake.calls);
    }

    // A throwing callback rolls back and the error keeps propagating
    @Test
    public void test_transaction_rolls_back_on_throw() {
        final var fake = new FakeDatabaseAccess();
        final var result = runScript(fake, """
                import db from "db";
                db.transaction(() => { throw new Error("boom"); });
                """);
        assertTrue(result.isError());
        assertEquals(List.of("beginTransaction", "rollbackTransaction"), fake.calls);
    }

    // A non-callable, an async callback and a generator callback are all rejected before begin
    @Test
    public void test_transaction_rejects_suspendable_callbacks() {
        for (final var callback : List.of("42", "async function () {}", "function* () {}")) {
            final var fake = new FakeDatabaseAccess();
            final var result = runScript(fake, "import db from \"db\";\ndb.transaction(" + callback + ");");
            assertTrue(result.isError(), callback);
            assertEquals("TypeError", result.getErrorName(), callback);
            assertTrue(fake.calls.isEmpty(), callback);
        }
    }

    // A callback that returns a promise cannot be awaited here, so it throws and rolls back
    @Test
    public void test_transaction_rejects_a_returned_promise() {
        final var fake = new FakeDatabaseAccess();
        final var result = runScript(fake, """
                import db from "db";
                db.transaction(() => Promise.resolve(1));
                """);
        assertTrue(result.isError());
        assertEquals(List.of("beginTransaction", "rollbackTransaction"), fake.calls);
    }

    // A nested db.transaction is rejected by the session guard rather than reaching a second START
    @Test
    public void test_nested_transaction_is_rejected() {
        final var fake = new FakeDatabaseAccess();
        fake.rejectNestedTransaction = true;
        final var result = runScript(fake, """
                import db from "db";
                db.transaction(() => db.transaction(() => 1));
                """);
        assertTrue(result.isError());
        assertEquals(List.of("beginTransaction", "beginTransaction", "rollbackTransaction"), fake.calls);
    }
}
