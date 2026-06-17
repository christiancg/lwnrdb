package org.techhouse.data.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;

public class AdminDbEntry extends DbEntry {
    private static final String COLLECTIONS_FIELD_NAME = "collections";
    private static final String OWNERS_FIELD_NAME = "owners";
    private List<String> collections;
    private List<String> owners;

    private AdminDbEntry() {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_DATABASES_COLLECTION_NAME);
    }

    public AdminDbEntry(String dbName) {
        this(dbName, new ArrayList<>(), new ArrayList<>());
    }

    public AdminDbEntry(String dbName, List<String> collections) {
        this(dbName, collections, new ArrayList<>());
    }

    public AdminDbEntry(String dbName, List<String> collections, List<String> owners) {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        this.set_id(dbName);
        this.collections = collections;
        this.owners = owners;
        rebuildData();
    }

    public static AdminDbEntry fromJsonObject(JsonObject object) {
        final var result = new AdminDbEntry();
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(id);
        final var collectionsEl = object.get(COLLECTIONS_FIELD_NAME);
        result.collections = collectionsEl != null && !collectionsEl.isJsonNull()
                ? collectionsEl.asJsonArray().asList().stream().map(element -> element.asJsonString().getValue())
                        .collect(Collectors.toList())
                : new ArrayList<>();
        final var ownersEl = object.get(OWNERS_FIELD_NAME);
        result.owners = ownersEl != null && !ownersEl.isJsonNull()
                ? ownersEl.asJsonArray().asList().stream().map(element -> element.asJsonString().getValue())
                        .collect(Collectors.toList())
                : new ArrayList<>();
        result.setDatabaseName(Globals.ADMIN_DB_NAME);
        result.setCollectionName(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        return result;
    }

    private void rebuildData() {
        final var data = new JsonObject();
        if (get_id() != null) {
            data.add(Globals.PK_FIELD, new org.techhouse.ejson.elements.JsonString(get_id()));
        }
        final var collectionsArr = new JsonArray();
        collections.forEach(collectionsArr::add);
        data.add(COLLECTIONS_FIELD_NAME, collectionsArr);
        final var ownersArr = new JsonArray();
        owners.forEach(ownersArr::add);
        data.add(OWNERS_FIELD_NAME, ownersArr);
        this.setData(data);
    }

    public void setCollections(List<String> collections) {
        this.collections = collections;
        rebuildData();
    }

    public List<String> getCollections() {
        return collections;
    }

    public void setOwners(List<String> owners) {
        this.owners = owners;
        rebuildData();
    }

    public List<String> getOwners() {
        return owners != null ? owners : new ArrayList<>();
    }

    public boolean isOwner(String username) {
        return owners != null && owners.contains(username);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AdminDbEntry that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(collections, that.collections) && Objects.equals(owners, that.owners);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), collections, owners);
    }

    @Override
    public String toString() {
        return "AdminDbEntry(super=" + super.toString() + ", collections=" + collections + ", owners=" + owners + ")";
    }
}
