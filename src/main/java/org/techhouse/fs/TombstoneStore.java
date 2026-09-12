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
import java.util.Map;
import java.util.function.ObjLongConsumer;
import org.techhouse.config.Globals;

final class TombstoneStore {
    private TombstoneStore() {
    }

    static void append(File file, String id, long version) throws IOException {
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try (var writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, true), Globals.BUFFER_SIZE)) {
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
        for (final var line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            final var cleaned = line.trim();
            final var sep = cleaned.lastIndexOf(Globals.INDEX_ENTRY_SEPARATOR);
            if (sep <= 0) {
                continue;
            }
            try {
                consumer.accept(cleaned.substring(0, sep), Long.parseLong(cleaned.substring(sep + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
