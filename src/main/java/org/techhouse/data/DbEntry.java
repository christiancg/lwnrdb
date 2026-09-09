package org.techhouse.data;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;

public class DbEntry extends JsonDocumentEntry {
    private long page;
    // Last-write-wins version (epoch millis), assigned by the coordinating owner and persisted in the PK
    // index. Not part of the document data or of equality; threaded through the write path for replication
    // and anti-entropy.
    private long version;

    public static DbEntry fromJsonObject(String databaseName, String collectionName, JsonObject jsonObject) {
        final var entry = new DbEntry();
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        entry.setData(jsonObject);
        entry.set_id(
                jsonObject.has(Globals.PK_FIELD) ? jsonObject.get(Globals.PK_FIELD).asJsonString().getValue() : null);
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

    public int byteSize() {
        if (data == null) {
            return 0;
        }
        return (toFileEntry() + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8).length;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof DbEntry that))
            return false;
        return page == that.page && previousByteSize == that.previousByteSize && Objects.equals(_id, that._id)
                && Objects.equals(databaseName, that.databaseName)
                && Objects.equals(collectionName, that.collectionName) && Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_id, databaseName, collectionName, data, page, previousByteSize);
    }

    @Override
    public String toString() {
        return "DbEntry(id=" + _id + ", databaseName=" + databaseName + ", collectionName=" + collectionName + ", data="
                + data + ", page=" + page + ", previousByteSize=" + previousByteSize + ")";
    }
}
