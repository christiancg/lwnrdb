package org.techhouse.data;

import lombok.Data;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

import java.util.UUID;

@Data
public class IndexedDbEntry {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private String _id;
    private String databaseName;
    private String collectionName;
    private JsonObject data;
    private PkIndexEntry index;

    public DbEntry toDbEntry() {
        final var entry = new DbEntry();
        entry.set_id(_id);
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        entry.setData(data);
        return entry;
    }

    public String toFileEntry() {
        if (_id == null) {
            _id = UUID.randomUUID().toString();
        }
        data.addProperty(Globals.PK_FIELD, _id);
        return eJson.toJson(data);
    }
}
