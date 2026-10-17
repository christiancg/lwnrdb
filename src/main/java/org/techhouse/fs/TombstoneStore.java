package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.FileLocks.Parsed;
import org.techhouse.log.Logger;

public final class TombstoneStore {
    private static final Logger logger = Logger.logFor(TombstoneStore.class);
    private static final String TOMBSTONE_ROLLBACK_FAILURE = "Could not roll back a failed tombstone append; the"
            + " tombstone file may hold a torn line, and nothing rebuilds it";

    private final FilePaths paths;

    TombstoneStore(FilePaths paths) {
        this.paths = paths;
    }

    public void append(String dbName, String collName, String id, long version) throws IOException {
        append(paths.tombstoneFile(dbName, collName), id, version);
    }

    public void retract(String dbName, String collName, String id, long version) throws IOException {
        retract(paths.tombstoneFile(dbName, collName), id, version);
    }

    public void appendAdmin(String key, long version) throws IOException {
        final var file = paths.adminTombstoneFile();
        MetadataFileStore.ensureFolder(file.getParentFile(), "cluster", "admin");
        append(file, key, version);
    }

    public Map<String, Long> readAdmin() throws IOException {
        return read(paths.adminTombstoneFile());
    }

    public Map<String, Long> read(String dbName, String collName) throws IOException {
        return read(paths.tombstoneFile(dbName, collName));
    }

    public void compact(String dbName, String collName, long minVersionToKeep) throws IOException {
        compact(paths.tombstoneFile(dbName, collName), minVersionToKeep);
    }

    static void append(File file, String id, long version) throws IOException {
        FileLocks.appendLines(file, List.of(id),
                tombstoned -> FieldIndexEntry.escapeIndexToken(tombstoned) + Globals.ID_SEPARATOR + version,
                TOMBSTONE_ROLLBACK_FAILURE);
    }

    static Map<String, Long> read(File file) throws IOException {
        if (!file.exists()) {
            return new HashMap<>();
        }
        final var parsed = FileLocks.readHealing(file, TombstoneStore::parseTombstones);
        if (parsed.unrecognised()) {
            logger.error("No line in " + file.getName() + " could be read as a tombstone entry, so it is not the"
                    + " file this loader expects; leaving it untouched. Nothing rebuilds the tombstone file, so it"
                    + " must never be rewritten from a read that understood none of it.");
        }
        return parsed.entries();
    }

    static void compact(File file, long minVersionToKeep) throws IOException {
        if (!file.exists()) {
            return;
        }
        final var lock = FileLocks.lockWrite(file);
        try {
            final var parsed = parseTombstones(file);
            if (parsed.unrecognised()) {
                logger.error("No line in " + file.getName() + " could be read as a tombstone entry; leaving it"
                        + " untouched rather than compacting it to nothing");
                return;
            }
            final var kept = new LinkedHashMap<String, Long>();
            for (final var entry : parsed.entries().entrySet()) {
                if (entry.getValue() >= minVersionToKeep) {
                    kept.put(entry.getKey(), entry.getValue());
                }
            }
            final var lines = new ArrayList<String>(kept.size());
            for (final var entry : kept.entrySet()) {
                lines.add(FieldIndexEntry.escapeIndexToken(entry.getKey()) + Globals.ID_SEPARATOR + entry.getValue());
            }
            FileLocks.rewriteFileAtomically(file.toPath(), lines);
        } finally {
            lock.unlock();
        }
    }

    static void retract(File file, String id, long version) throws IOException {
        if (!file.exists()) {
            return;
        }
        final var lock = FileLocks.lockWrite(file);
        try {
            final var parsed = parseTombstones(file);
            if (parsed.unrecognised()) {
                logger.error("No line in " + file.getName() + " could be read as a tombstone entry; leaving it"
                        + " untouched rather than retracting from it");
                return;
            }
            final var retracted = FieldIndexEntry.escapeIndexToken(id) + Globals.ID_SEPARATOR + version;
            final var kept = parsed.lines().stream().filter(line -> !line.equals(retracted)).toList();
            if (kept.size() != parsed.lines().size()) {
                FileLocks.rewriteFileAtomically(file.toPath(), kept);
            }
        } finally {
            lock.unlock();
        }
    }

    private static Parsed<Map<String, Long>> parseTombstones(File file) throws IOException {
        final var entries = new HashMap<String, Long>();
        final var lines = new ArrayList<String>();
        var dropped = false;
        for (final var line : FileLocks.decodeLines(Files.readAllBytes(file.toPath()))) {
            final var cleaned = line.replace("\r", "").replace("\n", "");
            if (cleaned.isEmpty()) {
                continue;
            }
            final var sep = cleaned.indexOf(Globals.ID_SEPARATOR);
            if (sep <= 0) {
                dropped = true;
                logger.warning("Removing malformed tombstone entry in " + file.getName() + ": no separator found");
                continue;
            }
            try {
                final var id = FieldIndexEntry.unescapeIndexToken(cleaned.substring(0, sep));
                final var version = Long.parseLong(cleaned.substring(sep + Globals.ID_SEPARATOR.length()));
                entries.merge(id, version, Math::max);
                lines.add(cleaned);
            } catch (NumberFormatException e) {
                dropped = true;
                logger.warning("Removing malformed tombstone entry in " + file.getName() + ": " + e.getMessage());
            }
        }
        return new Parsed<>(entries, lines, dropped, dropped && entries.isEmpty());
    }
}
