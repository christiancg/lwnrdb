package org.techhouse.unit.simplejs.host;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.exceptions.JsThrowException;
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
    public void delete(String db, String coll, String id) {
        calls.add("delete:" + db + "/" + coll + "/" + id);
        maybeDeny();
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
            final var error = new JsObject();
            error.set("name", new JsString("Error"));
            error.set("message", new JsString(denyMessage));
            throw new JsThrowException(error);
        }
    }

    private static JsonObject single() {
        final var object = new JsObject();
        object.set("_id", new JsString("agg1"));
        return (JsonObject) EJsonInterop.toEjson(object);
    }
}
