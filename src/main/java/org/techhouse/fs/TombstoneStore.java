package org.techhouse.fs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.log.Logger;

final class TombstoneStore {
    private static final Logger logger = Logger.logFor(TombstoneStore.class);
    private static final String TOMBSTONE_ROLLBACK_FAILURE = "Could not roll back a failed tombstone append; the"
            + " tombstone file may hold a torn line, and nothing rebuilds it";

    private TombstoneStore() {
    }

    static void append(File file, String id, long version) throws IOException {
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try {
            appendOrTruncateBack(file, id, version);
        } finally {
            lock.unlock();
        }
    }

    private static void appendOrTruncateBack(File file, String id, long version) throws IOException {
        final var lengthBeforeAppend = file.length();
        var appended = false;
        try {
            try (var writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, true),
                    Globals.BUFFER_SIZE)) {
                if (FileLocks.endsMidLine(file)) {
                    writer.newLine();
                }
                writer.append(FieldIndexEntry.escapeIndexToken(id)).append(Globals.ID_SEPARATOR)
                        .append(String.valueOf(version));
                writer.newLine();
            }
            appended = true;
        } finally {
            if (!appended) {
                PageRegions.truncateTo(file, lengthBeforeAppend, TOMBSTONE_ROLLBACK_FAILURE);
            }
        }
    }

    static Map<String, Long> read(File file) throws IOException {
        if (!file.exists()) {
            return new HashMap<>();
        }
        final var readLock = FileLocks.lockFor(file).readLock();
        readLock.lock();
        final ParsedTombstones parsed;
        try {
            parsed = parseTombstones(file);
        } finally {
            readLock.unlock();
        }
        if (parsed.unrecognised()) {
            logger.error("No line in " + file.getName() + " could be read as a tombstone entry, so it is not the"
                    + " file this loader expects; leaving it untouched. Nothing rebuilds the tombstone file, so it"
                    + " must never be rewritten from a read that understood none of it.");
            return parsed.entries();
        }
        if (!parsed.dropped()) {
            return parsed.entries();
        }
        final var writeLock = FileLocks.lockFor(file).writeLock();
        writeLock.lock();
        try {
            final var reparsed = parseTombstones(file);
            if (reparsed.dropped()) {
                FileLocks.rewriteFileAtomically(file.toPath(), reparsed.lines());
            }
            return reparsed.entries();
        } finally {
            writeLock.unlock();
        }
    }

    static void compact(File file, long minVersionToKeep) throws IOException {
        if (!file.exists()) {
            return;
        }
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
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
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
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

    private static ParsedTombstones parseTombstones(File file) throws IOException {
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
        return new ParsedTombstones(entries, lines, dropped, dropped && entries.isEmpty());
    }

    private record ParsedTombstones(Map<String, Long> entries, List<String> lines, boolean dropped,
            boolean unrecognised) {
    }
}
