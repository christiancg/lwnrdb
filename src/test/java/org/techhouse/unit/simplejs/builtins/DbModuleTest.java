package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.builtins.DbModule;
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
        final var db = DbModule.create(fake);
        final var result = call(db, "findById", new JsString("mydb"), new JsString("users"), new JsString("x1"));
        assertInstanceOf(JsObject.class, result);
        assertEquals("x1", ((JsString) ((JsObject) result).get("_id")).getValue());
        assertEquals("findById:mydb/users/x1", fake.calls.getFirst());
    }

    // A null document from findById converts to JsNull
    @Test
    public void test_find_by_id_missing() {
        final var fake = new FakeDatabaseAccess();
        final var db = DbModule.create(fake);
        final var result = call(db, "findById", new JsString("d"), new JsString("c"), new JsString("nope"));
        assertInstanceOf(JsNull.class, result);
    }

    // aggregate forwards the pipeline array and returns an array of results
    @Test
    public void test_aggregate() {
        final var fake = new FakeDatabaseAccess();
        final var db = DbModule.create(fake);
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
        final var db = DbModule.create(fake);
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
        final var db = DbModule.create(fake);
        final var result = call(db, "delete", new JsString("d"), new JsString("c"), new JsString("s1"));
        assertInstanceOf(JsUndefined.class, result);
        assertEquals("delete:d/c/s1", fake.calls.getFirst());
    }

    // listCollections and listDatabases return string arrays
    @Test
    public void test_list_operations() {
        final var fake = new FakeDatabaseAccess();
        final var db = DbModule.create(fake);
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
}
