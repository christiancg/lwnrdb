package org.techhouse.data;

import java.util.Objects;
import org.techhouse.config.Globals;

public class PkIndexEntry implements Comparable<String> {
    private String databaseName;
    private String collectionName;
    private String value;
    private long position;
    private long length;
    private long page;
    // Last-write-wins version (epoch millis), persisted as the optional trailing index column.
    private long version;

    public PkIndexEntry(String databaseName, String collectionName, String value, long position, long length,
            long page) {
        this(databaseName, collectionName, value, position, length, page, 0L);
    }

    public PkIndexEntry(String databaseName, String collectionName, String value, long position, long length, long page,
            long version) {
        this.databaseName = databaseName;
        this.collectionName = collectionName;
        this.value = value;
        this.position = position;
        this.length = length;
        this.page = page;
        this.version = version;
    }

    public String toFileEntry() {
        return value + Globals.INDEX_ENTRY_SEPARATOR + position + Globals.INDEX_ENTRY_SEPARATOR + length
                + Globals.INDEX_ENTRY_SEPARATOR + page + Globals.INDEX_ENTRY_SEPARATOR + version;
    }

    public static PkIndexEntry fromIndexFileEntry(String databaseName, String collectionName, String line) {
        final var cleaned = line.trim().replace("\r", "").replace("\n", "");
        final var sep = Globals.INDEX_ENTRY_SEPARATOR;
        // Lines are value|position|length|page|version. The value (a document id) may itself contain the
        // separator, so parse the four fixed trailing fields from the end and take the rest as the value.
        final var lastPipe = cleaned.lastIndexOf(sep);
        final var secondLastPipe = cleaned.lastIndexOf(sep, lastPipe - 1);
        final var thirdLastPipe = cleaned.lastIndexOf(sep, secondLastPipe - 1);
        final var fourthLastPipe = cleaned.lastIndexOf(sep, thirdLastPipe - 1);
        return new PkIndexEntry(databaseName, collectionName, cleaned.substring(0, fourthLastPipe),
                Long.parseLong(cleaned.substring(fourthLastPipe + sep.length(), thirdLastPipe)),
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

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    @Override
    public int compareTo(String otherIndexValue) {
        Objects.requireNonNull(otherIndexValue);
        return value.compareTo(otherIndexValue);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof PkIndexEntry that))
            return false;
        return position == that.position && length == that.length && page == that.page
                && Objects.equals(databaseName, that.databaseName)
                && Objects.equals(collectionName, that.collectionName) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseName, collectionName, value, position, length, page);
    }

    @Override
    public String toString() {
        return "PkIndexEntry(databaseName=" + databaseName + ", collectionName=" + collectionName + ", value=" + value
                + ", position=" + position + ", length=" + length + ", page=" + page + ")";
    }
}
