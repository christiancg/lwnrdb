package org.techhouse.data;

import java.util.Objects;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.JsonSizeEstimator;

public class DbEntry extends JsonDocumentEntry {
    private static final int ENCODE_SIZE_HINT = 1024;
    private static final int ESTIMATED_PRESIZE_ABOVE = 64 * 1024;
    private long page;
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

    public static DbEntry fromBytes(String databaseName, String collectionName, byte[] utf8, int start, int end) {
        final var entry = new DbEntry();
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        final var data = eJson.fromJson(utf8, start, end, JsonObject.class);
        entry.setData(data);
        entry.set_id(data.has(Globals.PK_FIELD) ? data.get(Globals.PK_FIELD).asJsonString().getValue() : null);
        return entry;
    }

    public int byteSize() {
        if (data == null) {
            return 0;
        }
        if (cachedByteSize < 0) {
            encodeLine();
        }
        return cachedByteSize;
    }

    public byte[] encodeLine() {
        stampId();
        final var line = eJson.toJsonLine(data, encodeSizeHint(), Globals.NEWLINE);
        cachedByteSize = line.length;
        return line;
    }

    private int encodeSizeHint() {
        if (cachedByteSize > 0) {
            return cachedByteSize;
        }
        final var estimate = JsonSizeEstimator.estimate(data);
        if (estimate <= ESTIMATED_PRESIZE_ABOVE) {
            return ENCODE_SIZE_HINT;
        }
        return (int) Math.min(estimate + estimate / 8 + Globals.NEWLINE.length(), Integer.MAX_VALUE);
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
