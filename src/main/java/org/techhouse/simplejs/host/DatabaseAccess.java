package org.techhouse.simplejs.host;

import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.values.JsObject;

public interface DatabaseAccess {
    // Errors thrown out of an implementation land in script `catch` blocks, so the interpreter hands
    // over its own realm's Error.prototype: an error built without it fails `e instanceof Error`.
    default void useErrorPrototype(JsObject prototype) {
    }

    JsonObject findById(String db, String coll, String id);

    List<JsonObject> aggregate(String db, String coll, JsonArray pipeline);

    JsonObject save(String db, String coll, JsonObject document);

    void delete(String db, String coll, String id);

    List<String> listCollections(String db);

    List<String> listDatabases();
}
