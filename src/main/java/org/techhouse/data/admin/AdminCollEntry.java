package org.techhouse.data.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class AdminCollEntry extends DbEntry {
    private static final String INDEXES_FIELD_NAME = "indexes";
    private static final String ENTRY_COUNT_FIELD_NAME = "entryCount";
    private List<String> indexes;
    private int entryCount;

    private AdminCollEntry() {
    }

    public AdminCollEntry(String dbName, String collName) {
        this(dbName, collName, new ArrayList<>(), 0);
    }

    public AdminCollEntry(String dbName, String collName, List<String> indexes, int entryCount) {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        this.set_id(Cache.getCollectionIdentifier(dbName, collName));
        this.indexes = indexes;
        this.entryCount = entryCount;
        final var json = new JsonObject();
        final var arr = new JsonArray();
        indexes.forEach(arr::add);
        json.add(INDEXES_FIELD_NAME, arr);
        json.addProperty(ENTRY_COUNT_FIELD_NAME, entryCount);
        this.setData(json);
    }

    public static AdminCollEntry fromJsonObject(JsonObject object) {
        final var result = new AdminCollEntry();
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).getAsString();
        result.set_id(id);
        final var collections = object.get(INDEXES_FIELD_NAME).getAsJsonArray().asList()
                .stream().map(element -> element.getAsJsonPrimitive().getAsString()).toList();
        result.setIndexes(collections);
        result.setDatabaseName(Globals.ADMIN_DB_NAME);
        result.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        return result;
    }

    public void setIndexes(List<String> indexes) {
        this.indexes = indexes;
        final var data = getData();
        final var arr = new JsonArray();
        indexes.forEach(arr::add);
        data.add(INDEXES_FIELD_NAME, arr);
        this.setData(data);
    }

    public void setEntryCount(int entryCount) {
        this.entryCount = entryCount;
        final var data = getData();
        data.addProperty(ENTRY_COUNT_FIELD_NAME, entryCount);
        this.setData(data);
    }

    @Override
    public void setDatabaseName(String value) {}
    @Override
    public void setCollectionName(String value) {}
}
