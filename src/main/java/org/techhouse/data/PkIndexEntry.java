package org.techhouse.data;

import org.techhouse.config.Globals;

import java.util.Objects;

public class PkIndexEntry implements Comparable<String> {
    private String databaseName;
    private String collectionName;
    private String value;
    private long position;
    private long length;
    private long page;

    public PkIndexEntry(String databaseName, String collectionName, String value, long position, long length, long page) {
        this.databaseName = databaseName;
        this.collectionName = collectionName;
        this.value = value;
        this.position = position;
        this.length = length;
        this.page = page;
    }

    public String toFileEntry() {
        return value + Globals.INDEX_ENTRY_SEPARATOR + position + Globals.INDEX_ENTRY_SEPARATOR + length + Globals.INDEX_ENTRY_SEPARATOR + page;
    }

    public static PkIndexEntry fromIndexFileEntry(String databaseName, String collectionName, String line) {
        final var cleaned = line.trim().replace("\r", "").replace("\n", "");
        final var sep = Globals.INDEX_ENTRY_SEPARATOR;
        final var lastPipe       = cleaned.lastIndexOf(sep);
        final var secondLastPipe = cleaned.lastIndexOf(sep, lastPipe - 1);
        final var thirdLastPipe  = cleaned.lastIndexOf(sep, secondLastPipe - 1);
        return new PkIndexEntry(databaseName, collectionName,
                cleaned.substring(0, thirdLastPipe),
                Long.parseLong(cleaned.substring(thirdLastPipe + sep.length(), secondLastPipe)),
                Long.parseLong(cleaned.substring(secondLastPipe + sep.length(), lastPipe)),
                Long.parseLong(cleaned.substring(lastPipe + sep.length())));
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

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    @Override
    public int compareTo(String otherIndexValue) {
        Objects.requireNonNull(otherIndexValue);
        return value.compareTo(otherIndexValue);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PkIndexEntry that)) return false;
        return position == that.position && length == that.length && page == that.page && Objects.equals(databaseName, that.databaseName) && Objects.equals(collectionName, that.collectionName) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseName, collectionName, value, position, length, page);
    }

    @Override
    public String toString() {
        return "PkIndexEntry(databaseName=" + databaseName + ", collectionName=" + collectionName + ", value=" + value + ", position=" + position + ", length=" + length + ", page=" + page + ")";
    }
}
