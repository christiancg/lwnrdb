package org.techhouse.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.techhouse.config.Globals;

@Data
@AllArgsConstructor
public class PkIndexEntry implements Comparable<String> {
    private String databaseName;
    private String collectionName;
    private String value;
    private long position;
    private long length;

    public String toFileEntry() {
        return value + Globals.INDEX_ENTRY_SEPARATOR + position + Globals.INDEX_ENTRY_SEPARATOR + length;
    }

    public static PkIndexEntry fromIndexFileEntry(String databaseName, String collectionName, String line) {
        final var parts = line.split(Globals.INDEX_ENTRY_SEPARATOR_REGEX);
        return new PkIndexEntry(databaseName, collectionName, parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
    }

    @Override
    public int compareTo(String otherIndexValue) {
        return value.compareTo(otherIndexValue);
    }
}
