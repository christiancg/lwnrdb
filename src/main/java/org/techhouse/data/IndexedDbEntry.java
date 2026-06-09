package org.techhouse.data;

import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

import java.util.Objects;
import java.util.UUID;

public class IndexedDbEntry {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private String _id;
    private String databaseName;
    private String collectionName;
    private JsonObject data;
    private PkIndexEntry index;
    private long previousByteSize;

    public DbEntry toDbEntry() {
        final var entry = new DbEntry();
        entry.set_id(_id);
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        entry.setData(data);
        if (index != null) {
            entry.setPage(index.getPage());
        }
        entry.setPreviousByteSize(previousByteSize);
        return entry;
    }

    public String toFileEntry() {
        if (_id == null) {
            _id = UUID.randomUUID().toString();
        }
        data.addProperty(Globals.PK_FIELD, _id);
        return eJson.toJson(data);
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public JsonObject getData() {
        return data;
    }

    public void setData(JsonObject data) {
        this.data = data;
    }

    public PkIndexEntry getIndex() {
        return index;
    }

    public void setIndex(PkIndexEntry index) {
        this.index = index;
    }

    public long getPreviousByteSize() {
        return previousByteSize;
    }

    public void setPreviousByteSize(long previousByteSize) {
        this.previousByteSize = previousByteSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexedDbEntry that)) return false;
        return previousByteSize == that.previousByteSize && Objects.equals(_id, that._id) && Objects.equals(databaseName, that.databaseName) && Objects.equals(collectionName, that.collectionName) && Objects.equals(data, that.data) && Objects.equals(index, that.index);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_id, databaseName, collectionName, data, index, previousByteSize);
    }

    @Override
    public String toString() {
        return "IndexedDbEntry(id=" + _id + ", databaseName=" + databaseName + ", collectionName=" + collectionName + ", data=" + data + ", index=" + index + ", previousByteSize=" + previousByteSize + ")";
    }
}
