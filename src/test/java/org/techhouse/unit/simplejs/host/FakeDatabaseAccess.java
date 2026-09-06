package org.techhouse.unit.simplejs.host;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.BulkSaveOutcome;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;

// A configurable in-memory DatabaseAccess double for interpreter-side tests. When denyMessage is set it
// throws a JS Error from every call, mimicking EnforcingDatabaseAccess's permission/schema rejection.
public class FakeDatabaseAccess implements DatabaseAccess {
    public final List<String> calls = new ArrayList<>();
    public JsonObject nextFindResult;
    public String denyMessage;
    public boolean rejectNestedTransaction;
    private boolean inTransaction;

    @Override
    public JsonObject findById(String db, String coll, String id) {
        calls.add("findById:" + db + "/" + coll + "/" + id);
        maybeDeny();
        return nextFindResult;
    }

    @Override
    public List<JsonObject> aggregate(String db, String coll, JsonArray pipeline) {
        calls.add("aggregate:" + db + "/" + coll + "/" + pipeline.size());
        maybeDeny();
        return List.of(single());
    }

    @Override
    public JsonObject save(String db, String coll, JsonObject document) {
        calls.add("save:" + db + "/" + coll);
        maybeDeny();
        return document;
    }

    @Override
    public BulkSaveOutcome bulkSave(String db, String coll, List<JsonObject> documents) {
        calls.add("bulkSave:" + db + "/" + coll + "/" + documents.size());
        maybeDeny();
        return new BulkSaveOutcome(List.of("b1"), List.of("b2"));
    }

    @Override
    public void delete(String db, String coll, String id) {
        calls.add("delete:" + db + "/" + coll + "/" + id);
        maybeDeny();
    }

    @Override
    public void beginTransaction() {
        calls.add("beginTransaction");
        maybeDeny();
        if (rejectNestedTransaction && inTransaction) {
            throwError("A transaction is already active on this script");
        }
        inTransaction = true;
    }

    @Override
    public void commitTransaction() {
        calls.add("commitTransaction");
        inTransaction = false;
    }

    @Override
    public void rollbackTransaction() {
        calls.add("rollbackTransaction");
        inTransaction = false;
    }

    @Override
    public List<String> listCollections(String db) {
        calls.add("listCollections:" + db);
        maybeDeny();
        return List.of("c1", "c2");
    }

    @Override
    public List<String> listDatabases() {
        calls.add("listDatabases");
        maybeDeny();
        return List.of("d1");
    }

    private void maybeDeny() {
        if (denyMessage != null) {
            throwError(denyMessage);
        }
    }

    private static void throwError(String message) {
        final var error = new JsObject();
        error.set("name", new JsString("Error"));
        error.set("message", new JsString(message));
        throw new JsThrowException(error);
    }

    private static JsonObject single() {
        final var object = new JsObject();
        object.set("_id", new JsString("agg1"));
        return (JsonObject) EJsonInterop.toEjson(object);
    }
}
