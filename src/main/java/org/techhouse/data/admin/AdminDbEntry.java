package org.techhouse.data.admin;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Data
public class AdminDbEntry extends DbEntry {
    private static final String COLLECTIONS_FIELD_NAME = "collections";
    private List<String> collections;

    private AdminDbEntry() {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_DATABASES_COLLECTION_NAME);
    }

    public AdminDbEntry(String dbName) {
        this(dbName, new ArrayList<>());
    }

    public AdminDbEntry(String dbName, List<String> collections) {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        this.set_id(dbName);
        this.collections = collections;
        final var json = new JsonObject();
        final var arr = new JsonArray();
        collections.forEach(arr::add);
        json.add(COLLECTIONS_FIELD_NAME, arr);
        this.setData(json);
    }

    public static AdminDbEntry fromJsonObject(JsonObject object) {
        final var result = new AdminDbEntry();
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(id);
        final var collections = object.get(COLLECTIONS_FIELD_NAME).asJsonArray().asList()
                .stream().map(element -> element.asJsonString().getValue())
                .collect(Collectors.toList());
        result.setCollections(collections);
        result.setDatabaseName(Globals.ADMIN_DB_NAME);
        result.setCollectionName(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        return result;
    }

    public void setCollections(List<String> collections) {
        this.collections = collections;
        final var data = getData();
        final var arr = new JsonArray();
        collections.forEach(arr::add);
        data.add(COLLECTIONS_FIELD_NAME, arr);
        this.setData(data);
    }

    @Override
    public void setDatabaseName(String value) {
    }

    @Override
    public void setCollectionName(String value) {
    }
}
