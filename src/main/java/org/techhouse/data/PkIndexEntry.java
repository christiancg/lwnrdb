package org.techhouse.data;

import java.util.Objects;
import org.techhouse.config.Globals;

public class PkIndexEntry extends CollectionScopedEntry implements Comparable<String> {
    private static final int FIELD_COUNT = 5;
    private String value;
    private long position;
    private long length;
    private long page;
    private long version;

    public PkIndexEntry(String databaseName, String collectionName, String value, long position, long length,
            long page) {
        this(databaseName, collectionName, value, position, length, page, 0L);
    }

    public PkIndexEntry detachedCopy() {
        return new PkIndexEntry(databaseName, collectionName, value, position, length, page, version);
    }

    public PkIndexEntry(String databaseName, String collectionName, String value, long position, long length, long page,
            long version) {
        super(databaseName, collectionName);
        this.value = value;
        this.position = position;
        this.length = length;
        this.page = page;
        this.version = version;
    }

    public String toFileEntry() {
        return FieldIndexEntry.escapeIndexToken(value) + Globals.ID_SEPARATOR + position + Globals.ID_SEPARATOR + length
                + Globals.ID_SEPARATOR + page + Globals.ID_SEPARATOR + version;
    }

    public static PkIndexEntry fromIndexFileEntry(String databaseName, String collectionName, String line) {
        final var cleaned = line.replace("\r", "").replace("\n", "");
        final var fields = cleaned.split(Globals.ID_SEPARATOR, -1);
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException("A PK index line must hold " + FIELD_COUNT + " separated fields");
        }
        return new PkIndexEntry(databaseName, collectionName, FieldIndexEntry.unescapeIndexToken(fields[0]),
                Long.parseLong(fields[1]), Long.parseLong(fields[2]), Long.parseLong(fields[3]),
                Long.parseLong(fields[4]));
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
    @SuppressWarnings("NullableProblems")
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
