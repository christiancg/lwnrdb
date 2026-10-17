package org.techhouse.data;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.techhouse.config.Globals;

public class PkIndexEntry extends CollectionScopedEntry implements Comparable<String> {
    private static final int FIELD_COUNT = 5;
    private static final int MAX_UNCHECKED_DIGITS = 18;
    private static final byte SEPARATOR = (byte) Globals.ID_SEPARATOR.charAt(0);
    private static final String DELETE_MARK = "-";
    private static final byte DELETE_MARK_BYTE = '-';
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
        final var bytes = line.replace("\r", "").replace("\n", "").getBytes(StandardCharsets.UTF_8);
        return fromIndexFileBytes(databaseName, collectionName, bytes, 0, bytes.length);
    }

    public static PkIndexEntry fromIndexFileBytes(String databaseName, String collectionName, byte[] line, int start,
            int end) {
        final var valueEnd = nextSeparator(line, start, end);
        final var positionEnd = nextSeparator(line, valueEnd + 1, end);
        final var lengthEnd = nextSeparator(line, positionEnd + 1, end);
        final var pageEnd = nextSeparator(line, lengthEnd + 1, end);
        if (pageEnd >= end || nextSeparator(line, pageEnd + 1, end) != end) {
            throw new IllegalArgumentException("A PK index line must hold " + FIELD_COUNT + " separated fields");
        }
        final var value = new String(line, start, valueEnd - start, StandardCharsets.UTF_8);
        return new PkIndexEntry(databaseName, collectionName, FieldIndexEntry.unescapeIndexToken(value),
                parseLong(line, valueEnd + 1, positionEnd), parseLong(line, positionEnd + 1, lengthEnd),
                parseLong(line, lengthEnd + 1, pageEnd), parseLong(line, pageEnd + 1, end));
    }

    public static String toDeleteRow(String id) {
        return FieldIndexEntry.escapeIndexToken(id) + Globals.ID_SEPARATOR + DELETE_MARK;
    }

    public static String deletedIdOf(byte[] line, int start, int end) {
        final var valueEnd = nextSeparator(line, start, end);
        if (valueEnd + 2 != end || line[valueEnd + 1] != DELETE_MARK_BYTE) {
            return null;
        }
        return FieldIndexEntry.unescapeIndexToken(new String(line, start, valueEnd - start, StandardCharsets.UTF_8));
    }

    private static int nextSeparator(byte[] line, int from, int end) {
        for (var index = from; index < end; index++) {
            if (line[index] == SEPARATOR) {
                return index;
            }
        }
        return end;
    }

    private static long parseLong(byte[] line, int from, int to) {
        if (from < to && to - from <= MAX_UNCHECKED_DIGITS) {
            var value = 0L;
            var index = from;
            while (index < to && line[index] >= '0' && line[index] <= '9') {
                value = value * 10 + (line[index] - '0');
                index++;
            }
            if (index == to) {
                return value;
            }
        }
        return Long.parseLong(new String(line, from, to - from, StandardCharsets.UTF_8));
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
