package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ResidentTombstones {
    record FileState(boolean exists, long length, FileTime modified) {
        private static final FileState MISSING = new FileState(false, 0L, null);

        static FileState of(File file) throws IOException {
            try {
                final var attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
                return new FileState(true, attributes.size(), attributes.lastModifiedTime());
            } catch (NoSuchFileException e) {
                return MISSING;
            }
        }
    }

    private record Shape(FileState state, long lineCount, long oldest, long highest) {
    }

    private final Map<String, Long> versions;
    private volatile Shape shape;

    private ResidentTombstones(Map<String, Long> versions, long lineCount, FileState state) {
        this.versions = versions;
        var low = Long.MAX_VALUE;
        var high = 0L;
        for (final var version : versions.values()) {
            low = Math.min(low, version);
            high = Math.max(high, version);
        }
        this.shape = new Shape(state, lineCount, versions.isEmpty() ? 0L : low, high);
    }

    static ResidentTombstones of(Map<String, Long> entries, long lineCount, FileState state) {
        return new ResidentTombstones(new ConcurrentHashMap<>(entries), lineCount, state);
    }

    static ResidentTombstones unrecognised() {
        return new ResidentTombstones(Map.of(), 0L, FileState.MISSING);
    }

    boolean describes(FileState current) {
        return shape.state().equals(current);
    }

    Map<String, Long> versions() {
        return versions;
    }

    long lineCount() {
        return shape.lineCount();
    }

    long oldest() {
        return shape.oldest();
    }

    TombstoneView view() {
        final var current = shape;
        return new TombstoneView(versions, current.oldest(), current.highest());
    }

    void merge(List<String> ids, List<Long> appended, FileState after) {
        final var before = shape;
        var low = before.oldest();
        var high = before.highest();
        for (var i = 0; i < ids.size(); i++) {
            final long version = appended.get(i);
            versions.merge(ids.get(i), version, Math::max);
            low = low == 0L ? version : Math.min(low, version);
            high = Math.max(high, version);
        }
        shape = new Shape(after, before.lineCount() + ids.size(), low, high);
    }
}
