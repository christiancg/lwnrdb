package org.techhouse.data;

import lombok.Data;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.JsonObject;
import org.techhouse.ioc.IocContainer;

import java.util.UUID;

@Data
public class DbEntry {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private String _id;
    private String databaseName;
    private String collectionName;
    private JsonObject data;

    public static DbEntry fromJsonObject(String databaseName, String collectionName, JsonObject jsonObject) {
        final var entry = new DbEntry();
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        entry.setData(jsonObject);
        entry.set_id(jsonObject.has(Globals.PK_FIELD) ? jsonObject.get(Globals.PK_FIELD).getAsString() : null);
        return entry;
    }

    public static DbEntry fromString(String databaseName, String collectionName, String wholeEntryFromFile) {
        final var entry = new DbEntry();
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        final var data = eJson.fromJson(wholeEntryFromFile, JsonObject.class);
        entry.setData(data);
        entry.set_id(data.has(Globals.PK_FIELD) ? data.get(Globals.PK_FIELD).getAsString() : null);
        return entry;
    }

    public String toFileEntry() {
        if (_id == null) {
            _id = UUID.randomUUID().toString();
        }
        data.addProperty(Globals.PK_FIELD, _id);
        return data.toString();
    }
}
