package org.techhouse.data.admin;

import java.util.Objects;
import org.techhouse.cache.AccessKind;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

public class AdminCollectionUsageEntry extends DbEntry {
    private static final String KIND_FIELD = "kind";
    private static final String DB_NAME_FIELD = "dbName";
    private static final String COLL_NAME_FIELD = "collName";
    private static final String INDEX_KEY_FIELD = "indexKey";
    private static final String ACCESS_COUNT_FIELD = "accessCount";
    private static final String LAST_ACCESS_FIELD = "lastAccessMillis";

    private AccessKind kind;
    private String dbName;
    private String collName;
    private String indexKey;
    private long accessCount;
    private long lastAccessMillis;

    private AdminCollectionUsageEntry() {
        setDatabaseName(Globals.ADMIN_DB_NAME);
        setCollectionName(Globals.ADMIN_COLLECTION_USAGE_NAME);
        setData(new JsonObject());
    }

    public AdminCollectionUsageEntry(AccessKind kind, String dbName, String collName, String indexKey, long accessCount,
            long lastAccessMillis) {
        setDatabaseName(Globals.ADMIN_DB_NAME);
        setCollectionName(Globals.ADMIN_COLLECTION_USAGE_NAME);
        this.kind = kind;
        this.dbName = dbName;
        this.collName = collName;
        this.indexKey = indexKey == null ? "" : indexKey;
        this.accessCount = accessCount;
        this.lastAccessMillis = lastAccessMillis;
        set_id(buildId(dbName, collName, this.indexKey));
        setData(new JsonObject());
        syncData();
    }

    public static String buildId(String dbName, String collName, String indexKey) {
        final var key = indexKey == null ? "" : indexKey;
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + key;
    }

    public static AdminCollectionUsageEntry fromJsonObject(JsonObject object) {
        final var result = new AdminCollectionUsageEntry();
        result.setData(object);
        result.set_id(object.get(Globals.PK_FIELD).asJsonString().getValue());
        result.kind = AccessKind.valueOf(object.get(KIND_FIELD).asJsonString().getValue());
        result.dbName = object.get(DB_NAME_FIELD).asJsonString().getValue();
        result.collName = object.get(COLL_NAME_FIELD).asJsonString().getValue();
        result.indexKey = object.get(INDEX_KEY_FIELD).asJsonString().getValue();
        result.accessCount = Long.parseLong(object.get(ACCESS_COUNT_FIELD).asJsonString().getValue());
        result.lastAccessMillis = Long.parseLong(object.get(LAST_ACCESS_FIELD).asJsonString().getValue());
        return result;
    }

    private void syncData() {
        final var data = getData();
        if (data == null) {
            return;
        }
        data.addProperty(KIND_FIELD, kind.name());
        data.addProperty(DB_NAME_FIELD, dbName);
        data.addProperty(COLL_NAME_FIELD, collName);
        data.addProperty(INDEX_KEY_FIELD, indexKey);
        data.addProperty(ACCESS_COUNT_FIELD, Long.toString(accessCount));
        data.addProperty(LAST_ACCESS_FIELD, Long.toString(lastAccessMillis));
    }

    public AccessKind getKind() {
        return kind;
    }

    public String getDbName() {
        return dbName;
    }

    public String getCollName() {
        return collName;
    }

    public String getIndexKey() {
        return indexKey;
    }

    public long getAccessCount() {
        return accessCount;
    }

    public void setAccessCount(long accessCount) {
        this.accessCount = accessCount;
        syncData();
    }

    public long getLastAccessMillis() {
        return lastAccessMillis;
    }

    public void setLastAccessMillis(long lastAccessMillis) {
        this.lastAccessMillis = lastAccessMillis;
        syncData();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AdminCollectionUsageEntry that))
            return false;
        if (!super.equals(o))
            return false;
        return accessCount == that.accessCount && lastAccessMillis == that.lastAccessMillis && kind == that.kind
                && Objects.equals(dbName, that.dbName) && Objects.equals(collName, that.collName)
                && Objects.equals(indexKey, that.indexKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), kind, dbName, collName, indexKey, accessCount, lastAccessMillis);
    }

    @Override
    public String toString() {
        return "AdminCollectionUsageEntry(super=" + super.toString() + ", kind=" + kind + ", dbName=" + dbName
                + ", collName=" + collName + ", indexKey=" + indexKey + ", accessCount=" + accessCount
                + ", lastAccessMillis=" + lastAccessMillis + ")";
    }
}
