package org.techhouse.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import org.techhouse.config.Globals;

@Data
@AllArgsConstructor
public class PkIndexEntry implements Comparable<String> {
    private String databaseName;
    private String collectionName;
    private String value;
    private long position;
    private long length;
    private long page;

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

    @Override
    public int compareTo(@NonNull String otherIndexValue) {
        return value.compareTo(otherIndexValue);
    }
}
