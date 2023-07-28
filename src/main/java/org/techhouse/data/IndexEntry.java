package org.techhouse.data;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class IndexEntry {
    private String databaseName;
    private String collectionName;
    private String value;
    private long position;
    private long length;

    public String toFileEntry() {
        return value + "|" + position + "|" + length;
    }

    public static IndexEntry fromIndexFileEntry(String databaseName, String collectionName, String line) {
        final var parts = line.split("\\|");
        return new IndexEntry(databaseName, collectionName, parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
    }
}
