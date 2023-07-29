package org.techhouse.data;

import com.google.gson.JsonObject;
import lombok.Data;

import java.util.UUID;

@Data
public class DbEntry {
    private String _id;
    private String databaseName;
    private String collectionName;
    private JsonObject data;

    public static DbEntry fromJsonObject(String databaseName, String collectionName, JsonObject jsonObject) {
        final var entry = new DbEntry();
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        entry.setData(jsonObject);
        entry.set_id(jsonObject.has("_id") ? jsonObject.get("_id").getAsString() : null);
        return entry;
    }

    public String toFileEntry() {
        if (_id == null) {
            _id = UUID.randomUUID().toString();
        }
        data.addProperty("_id", _id);
        return data.toString();
    }
}
