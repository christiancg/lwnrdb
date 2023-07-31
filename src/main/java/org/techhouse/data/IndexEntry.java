package org.techhouse.data;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

@Data
@AllArgsConstructor
public class IndexEntry implements Comparable<String> {
    private String databaseName;
    private String collectionName;
    private String value;
    private Set<String> ids;
    private long position;
    private long length;

    public String toFileEntry() {
        return value + '|' + String.join(";", ids) + '|' + position + '|' + length;
    }

    public static IndexEntry fromIndexFileEntry(String databaseName, String collectionName, String line) {
        final var parts = line.split("\\|");
        return new IndexEntry(databaseName, collectionName, parts[0], Set.of(parts[1].split(";")),
                Long.parseLong(parts[2]), Long.parseLong(parts[3]));
    }

    @Override
    public int compareTo(String otherIndexValue) {
        return value.compareTo(otherIndexValue);
    }
}
