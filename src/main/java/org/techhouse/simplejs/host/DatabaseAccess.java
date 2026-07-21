package org.techhouse.simplejs.host;

import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;

public interface DatabaseAccess {
    JsonObject findById(String db, String coll, String id);

    List<JsonObject> aggregate(String db, String coll, JsonArray pipeline);

    JsonObject save(String db, String coll, JsonObject document);

    void delete(String db, String coll, String id);

    List<String> listCollections(String db);

    List<String> listDatabases();
}
