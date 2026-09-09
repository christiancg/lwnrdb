package org.techhouse.fs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ObjLongConsumer;
import org.techhouse.config.Globals;

// The cluster's delete tombstones (id|version), append-only and deduplicated on read by keeping the
// highest version per id. A torn line is skipped rather than failing the read, mirroring the
// self-healing index loaders.
final class TombstoneStore {
    private TombstoneStore() {
    }

    static void append(File file, String id, long version) throws IOException {
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try (var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
            writer.append(id).append(Globals.INDEX_ENTRY_SEPARATOR).append(String.valueOf(version));
            writer.newLine();
        } finally {
            lock.unlock();
        }
    }

    static Map<String, Long> read(File file) throws IOException {
        final var result = new HashMap<String, Long>();
        if (!file.exists()) {
            return result;
        }
        final var lock = FileLocks.lockFor(file).readLock();
        lock.lock();
        try {
            forEachEntry(file, (id, version) -> result.merge(id, version, Math::max));
        } finally {
            lock.unlock();
        }
        return result;
    }

    // Keeps only the highest version per id and drops any tombstone older than minVersionToKeep (an
    // epoch-millis cutoff), so this both deduplicates the append-only file and removes fully-converged
    // deletes. A missing file is left untouched.
    static void compact(File file, long minVersionToKeep) throws IOException {
        if (!file.exists()) {
            return;
        }
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try {
            final var kept = new LinkedHashMap<String, Long>();
            forEachEntry(file, (id, version) -> {
                if (version >= minVersionToKeep) {
                    kept.merge(id, version, Math::max);
                }
            });
            final var lines = new ArrayList<String>(kept.size());
            for (final var entry : kept.entrySet()) {
                lines.add(entry.getKey() + Globals.INDEX_ENTRY_SEPARATOR + entry.getValue());
            }
            FileLocks.rewriteFileAtomically(file.toPath(), lines);
        } finally {
            lock.unlock();
        }
    }

    private static void forEachEntry(File file, ObjLongConsumer<String> consumer) throws IOException {
        for (final var line : Files.readAllLines(file.toPath())) {
            final var cleaned = line.trim();
            final var sep = cleaned.lastIndexOf(Globals.INDEX_ENTRY_SEPARATOR);
            if (sep <= 0) {
                continue;
            }
            try {
                consumer.accept(cleaned.substring(0, sep), Long.parseLong(cleaned.substring(sep + 1)));
            } catch (NumberFormatException ignored) {
                // A torn line names no usable version, so it cannot participate in the merge.
            }
        }
    }
}
