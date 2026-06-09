package org.techhouse.data.admin;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AdminCollEntry extends DbEntry {
    private static final String INDEXES_FIELD_NAME = "indexes";
    private Set<String> indexes;

    private AdminCollEntry() {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
    }

    public AdminCollEntry(String dbName, String collName) {
        this(dbName, collName, new HashSet<>());
    }

    public AdminCollEntry(String dbName, String collName, Set<String> indexes) {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        this.set_id(Cache.getCollectionIdentifier(dbName, collName));
        this.indexes = indexes;
        final var json = new JsonObject();
        final var arr = new JsonArray();
        indexes.forEach(arr::add);
        json.add(INDEXES_FIELD_NAME, arr);
        this.setData(json);
    }

    public static AdminCollEntry fromJsonObject(JsonObject object) {
        final var result = new AdminCollEntry();
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(id);
        final var collections = object.get(INDEXES_FIELD_NAME).asJsonArray().asList()
                .stream().map(element -> element.asJsonString().getValue())
                .collect(Collectors.toSet());
        result.setIndexes(collections);
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

    public Set<String> getIndexes() {
        return indexes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdminCollEntry that)) return false;
        if (!super.equals(o)) return false;
        return Objects.equals(indexes, that.indexes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), indexes);
    }

    @Override
    public String toString() {
        return "AdminCollEntry(super=" + super.toString() + ", indexes=" + indexes + ")";
    }
}
