package org.techhouse.simplejs.host;

import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.values.JsObject;

public interface DatabaseAccess {
    default void useErrorPrototype(JsObject prototype) {
    }

    default String scopedDatabase() {
        return null;
    }

    JsonObject findById(String db, String coll, String id);

    List<JsonObject> aggregate(String db, String coll, JsonArray pipeline);

    JsonObject save(String db, String coll, JsonObject document);

    BulkSaveOutcome bulkSave(String db, String coll, List<JsonObject> documents);

    void delete(String db, String coll, String id);

    List<String> listCollections(String db);

    List<String> listDatabases();

    void beginTransaction();

    void commitTransaction();

    void rollbackTransaction();
}
