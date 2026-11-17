package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.fs.ResidentTombstones.FileState;
import org.techhouse.log.Logger;

final class ResidentAdminTombstones {
    private static final Logger logger = Logger.logFor(ResidentAdminTombstones.class);
    private static final Map<String, ResidentAdminTombstones> residents = new ConcurrentHashMap<>();
    private static final int COMPACT_AT_LINES_PER_KEY = 2;

    private final Map<String, Long> versions;
    private final Map<String, Long> view;
    private volatile FileState state;
    private final AtomicInteger lineCount;

    private ResidentAdminTombstones(Map<String, Long> versions, int lineCount) {
        this.versions = versions;
        this.view = Collections.unmodifiableMap(versions);
        this.lineCount = new AtomicInteger(lineCount);
    }

    static Map<String, Long> read(File file) throws IOException {
        final var cached = residents.get(file.getAbsolutePath());
        if (cached != null && cached.state.equals(FileState.of(file))) {
            return cached.view;
        }
        final var lock = FileLocks.lockWrite(file);
        try {
            final var loaded = loadLocked(file);
            return loaded == null ? Map.of() : loaded.view;
        } finally {
            lock.unlock();
        }
    }

    static void append(File file, String key, long version, String rollbackFailure) throws IOException {
        final var lock = FileLocks.lockWrite(file);
        try {
            final var resident = loadLocked(file);
            FileLocks.appendLines(file, List.of(key), tombstoned -> TombstoneStore.lineOf(tombstoned, version),
                    rollbackFailure);
            if (resident == null) {
                return;
            }
            resident.versions.merge(key, version, Math::max);
            resident.lineCount.incrementAndGet();
            resident.state = FileState.of(file);
            if (resident.compactionDue()) {
                resident.compactQuietly(file);
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean compactionDue() {
        return !versions.isEmpty() && lineCount.get() >= COMPACT_AT_LINES_PER_KEY * (long) versions.size();
    }

    private static ResidentAdminTombstones loadLocked(File file) throws IOException {
        final var key = file.getAbsolutePath();
        final var state = FileState.of(file);
        final var cached = residents.get(key);
        if (cached != null && cached.state.equals(state)) {
            return cached;
        }
        final ResidentAdminTombstones loaded;
        if (state.exists()) {
            final var parsed = TombstoneFile.read(file);
            if (parsed.unrecognised()) {
                residents.remove(key);
                logger.error("No line in " + file.getName() + " could be read as an admin tombstone; leaving it"
                        + " untouched. Admin tombstones are permanent and nothing rebuilds them, so the file is"
                        + " never rewritten from a read that understood none of it.");
                return null;
            }
            final var versions = new ConcurrentHashMap<String, Long>(Math.max(16, parsed.chunks().size() * 2));
            parsed.chunks().forEach(versions::put);
            loaded = new ResidentAdminTombstones(versions, parsed.lines());
            loaded.state = FileState.of(file);
            if (parsed.dropped()) {
                loaded.rewrite(file);
            } else if (loaded.compactionDue()) {
                loaded.compactQuietly(file);
            }
        } else {
            loaded = new ResidentAdminTombstones(new ConcurrentHashMap<>(), 0);
            loaded.state = state;
        }
        residents.put(key, loaded);
        return loaded;
    }

    private void compactQuietly(File file) {
        try {
            rewrite(file);
        } catch (IOException e) {
            logger.warning("Could not compact " + file.getName() + " to one line per admin tombstone; it keeps"
                    + " every line and is compacted on a later drop: " + e.getMessage());
        }
    }

    private void rewrite(File file) throws IOException {
        final var lines = new ArrayList<String>(versions.size());
        for (final var entry : new TreeMap<>(versions).entrySet()) {
            lines.add(TombstoneStore.lineOf(entry.getKey(), entry.getValue()));
        }
        FileLocks.rewriteFileAtomically(file.toPath(), lines);
        lineCount.set(lines.size());
        state = FileState.of(file);
    }
}
