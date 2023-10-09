package org.techhouse.data.admin;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.JsonArray;
import org.techhouse.ejson.JsonObject;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Data
public class AdminCollEntry extends DbEntry {
    private static final String INDEXES_FIELD_NAME = "indexes";
    private static final String ENTRY_COUNT_FIELD_NAME = "entryCount";
    private Set<String> indexes;
    private int entryCount;

    private AdminCollEntry() {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
    }

    public AdminCollEntry(String dbName, String collName) {
        this(dbName, collName, new HashSet<>(), 0);
    }

    public AdminCollEntry(String dbName, String collName, Set<String> indexes, int entryCount) {
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
                .stream().map(element -> element.getAsJsonPrimitive().getAsString())
                .collect(Collectors.toSet());
        result.setIndexes(collections);
        final var entryCount = object.get(ENTRY_COUNT_FIELD_NAME).getAsInt();
        result.setEntryCount(entryCount);
        result.setDatabaseName(Globals.ADMIN_DB_NAME);
        result.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        return result;
    }

    public void setIndexes(Set<String> indexes) {
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
