package org.techhouse.data;

import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

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

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
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
        if (!(o instanceof DbEntry that)) return false;
        return page == that.page && previousByteSize == that.previousByteSize && Objects.equals(_id, that._id) && Objects.equals(databaseName, that.databaseName) && Objects.equals(collectionName, that.collectionName) && Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_id, databaseName, collectionName, data, page, previousByteSize);
    }

    @Override
    public String toString() {
        return "DbEntry(id=" + _id + ", databaseName=" + databaseName + ", collectionName=" + collectionName + ", data=" + data + ", page=" + page + ", previousByteSize=" + previousByteSize + ")";
    }
}
