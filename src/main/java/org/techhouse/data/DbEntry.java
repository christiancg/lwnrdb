package org.techhouse.data;

import lombok.Data;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Data
public class DbEntry {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private String _id;
    private String databaseName;
    private String collectionName;
    private JsonObject data;
    private long page;
    // Pre-update byte size for the corresponding file entry. Only set for updates,
    // so that page-size accounting can compute the size delta after an update.
    private long previousByteSize;

    public static DbEntry fromJsonObject(String databaseName, String collectionName, JsonObject jsonObject) {
        final var entry = new DbEntry();
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        entry.setData(jsonObject);
        entry.set_id(jsonObject.has(Globals.PK_FIELD) ? jsonObject.get(Globals.PK_FIELD).asJsonString().getValue() : null);
        return entry;
    }

    public static DbEntry fromString(String databaseName, String collectionName, String wholeEntryFromFile) {
        final var entry = new DbEntry();
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        final var data = eJson.fromJson(wholeEntryFromFile, JsonObject.class);
        entry.setData(data);
        entry.set_id(data.has(Globals.PK_FIELD) ? data.get(Globals.PK_FIELD).asJsonString().getValue() : null);
        return entry;
    }

    public String toFileEntry() {
        if (_id == null) {
            _id = UUID.randomUUID().toString();
        }
        data.addProperty(Globals.PK_FIELD, _id);
        return eJson.toJson(data);
    }

    public int byteSize() {
        if (data == null) {
            return 0;
        }
        return (toFileEntry() + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8).length;
    }
}
