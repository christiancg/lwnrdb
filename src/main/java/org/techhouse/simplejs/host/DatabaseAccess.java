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

    // The single database a script is restricted to, or null when unrestricted. Exposed to the script
    // as `db.name` so one script can run against any database instead of hardcoding the name.
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

    // A transaction holds each written collection's exclusive write lock across calls, and the lock is
    // thread-owned (ResourceLocking.releaseWrite silently no-ops from another thread, leaking it), so
    // an implementation must pin the session to the thread that opened it.
    void beginTransaction();

    void commitTransaction();

    void rollbackTransaction();
}
