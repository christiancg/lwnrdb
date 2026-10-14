package org.techhouse.fs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;

final class LegacyStorageOracle {
    private LegacyStorageOracle() {
    }

    static List<String> decodeLines(byte[] bytes) throws IOException {
        try (var reader = new BufferedReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            return reader.lines().toList();
        }
    }

    static byte[] joinLines(List<String> lines) {
        var chars = 0;
        for (final var line : lines) {
            chars += line.length() + Globals.NEWLINE.length();
        }
        final var builder = new StringBuilder(chars);
        for (final var line : lines) {
            builder.append(line).append(Globals.NEWLINE);
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] encodeMarker(CompactionJournal.Marker marker) {
        final var header = String.join(Globals.ID_SEPARATOR, marker.kind().name(),
                FieldIndexEntry.escapeIndexToken(marker.dbName()), FieldIndexEntry.escapeIndexToken(marker.collName()),
                Long.toString(marker.page()), Long.toString(marker.position()), Long.toString(marker.originalLength()),
                Long.toString(marker.removedLength()), FieldIndexEntry.escapeIndexToken(marker.id()),
                Long.toString(marker.version()), Long.toString(marker.targetPage()),
                Long.toString(marker.targetLengthBefore()));
        final var headerBytes = header.getBytes(StandardCharsets.UTF_8);
        final var encoded = new byte[headerBytes.length + 1 + marker.tail().length];
        System.arraycopy(headerBytes, 0, encoded, 0, headerBytes.length);
        encoded[headerBytes.length] = '\n';
        System.arraycopy(marker.tail(), 0, encoded, headerBytes.length + 1, marker.tail().length);
        return encoded;
    }

    static void updatePkIndex(File indexFile, String dbName, String collName, String value,
            PkIndexEntry newPkIndexEntry) throws IOException {
        final var rows = indexFile.exists() ? parse(indexFile, dbName, collName) : new ArrayList<PkIndexEntry>();
        if (rows == null) {
            throw new IOException("unrecognised");
        }
        final var changed = reindexPks(value, newPkIndexEntry, rows);
        changed.sort(Comparator.comparing(PkIndexEntry::getValue));
        Files.write(indexFile.toPath(), joinLines(changed.stream().map(PkIndexEntry::toFileEntry).toList()));
    }

    private static List<PkIndexEntry> parse(File indexFile, String dbName, String collName) throws IOException {
        final var byValue = new LinkedHashMap<String, PkIndexEntry>();
        boolean dropped = false;
        for (var line : decodeLines(Files.readAllBytes(indexFile.toPath()))) {
            if (line.isEmpty())
                continue;
            try {
                final var entry = PkIndexEntry.fromIndexFileEntry(dbName, collName, line);
                if (byValue.put(entry.getValue(), entry) != null) {
                    dropped = true;
                }
            } catch (Exception e) {
                dropped = true;
            }
        }
        final var entries = new ArrayList<>(byValue.values());
        return dropped && entries.isEmpty() ? null : entries;
    }

    private static List<PkIndexEntry> reindexPks(String value, PkIndexEntry newPkIndexEntry, List<PkIndexEntry> rows) {
        PkIndexEntry oldEntry = null;
        final var others = new ArrayList<PkIndexEntry>(rows.size());
        for (final var entry : rows) {
            if (entry.getValue().equals(value)) {
                oldEntry = entry;
            } else {
                others.add(entry);
            }
        }
        if (oldEntry != null) {
            for (final var entry : others) {
                if (entry.getPage() == oldEntry.getPage() && entry.getPosition() > oldEntry.getPosition()) {
                    entry.setPosition(entry.getPosition() - oldEntry.getLength());
                }
            }
        }
        if (newPkIndexEntry != null) {
            if (oldEntry != null) {
                newPkIndexEntry.setPosition(newPkIndexEntry.getPosition() - oldEntry.getLength());
            }
            others.add(newPkIndexEntry);
        }
        return others;
    }
}
