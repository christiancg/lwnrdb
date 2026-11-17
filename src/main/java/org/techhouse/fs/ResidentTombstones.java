package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ResidentTombstones {
    static final int MIN_BUFFERED = 1024;
    static final int BUFFER_DIVISOR = 64;
    static final long BUFFERED_ENTRY_BYTES = 112L;

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

    private record Layers(TombstoneChunks sorted, Map<String, Long> buffer, int size) {
    }

    private volatile Layers layers;
    private volatile Shape shape;

    private ResidentTombstones(TombstoneChunks sorted, long lineCount, FileState state) {
        final var range = sorted.versionRange();
        this.layers = new Layers(sorted, new ConcurrentHashMap<>(), sorted.size());
        this.shape = new Shape(state, lineCount, range[0], range[1]);
    }

    static ResidentTombstones of(TombstoneChunks sorted, long lineCount, FileState state) {
        return new ResidentTombstones(sorted, lineCount, state);
    }

    static ResidentTombstones unrecognised() {
        return new ResidentTombstones(TombstoneChunks.EMPTY, 0L, FileState.MISSING);
    }

    boolean describes(FileState current) {
        return shape.state().equals(current);
    }

    long lineCount() {
        return shape.lineCount();
    }

    long oldest() {
        return shape.oldest();
    }

    int size() {
        return layers.size();
    }

    long bytes() {
        final var current = layers;
        return current.sorted().bytes() + current.buffer().size() * BUFFERED_ENTRY_BYTES;
    }

    boolean hasBuffered() {
        return !layers.buffer().isEmpty();
    }

    long versionOf(String id) {
        final var current = layers;
        final var buffered = current.buffer().get(id);
        final var stored = current.sorted().versionOf(id);
        final var highest = Math.max(buffered == null ? TombstoneChunks.ABSENT : buffered, stored);
        return highest == TombstoneChunks.ABSENT ? 0L : highest;
    }

    void forEach(TombstoneChunks.IdConsumer action) {
        final var current = layers;
        final var buffer = current.buffer();
        if (buffer.isEmpty()) {
            current.sorted().forEach(action);
            return;
        }
        current.sorted().forEach((id, version) -> {
            final var buffered = buffer.get(id);
            action.accept(id, buffered == null ? version : Math.max(version, buffered));
        });
        buffer.forEach((id, version) -> {
            if (current.sorted().versionOf(id) == TombstoneChunks.ABSENT) {
                action.accept(id, version);
            }
        });
    }

    Map<String, Long> copy() {
        final var copy = new HashMap<String, Long>(Math.max(16, size() * 2));
        forEach(copy::put);
        return copy;
    }

    TombstoneView view() {
        final var current = shape;
        return new TombstoneView(this, current.oldest(), current.highest());
    }

    TombstoneChunks merged() {
        final var current = layers;
        return current.sorted().merged(current.buffer());
    }

    void mergeBuffer() {
        final var current = layers;
        if (!current.buffer().isEmpty()) {
            layers = new Layers(current.sorted().merged(current.buffer()), new ConcurrentHashMap<>(), current.size());
        }
    }

    void merge(List<String> ids, List<Long> appended, FileState after) {
        final var before = shape;
        final var current = layers;
        var low = before.oldest();
        var high = before.highest();
        var size = current.size();
        for (var i = 0; i < ids.size(); i++) {
            final var id = ids.get(i);
            final long version = appended.get(i);
            final var previous = current.buffer().get(id);
            if (previous == null && current.sorted().versionOf(id) == TombstoneChunks.ABSENT) {
                size++;
            }
            current.buffer().merge(id, version, Math::max);
            low = low == 0L ? version : Math.min(low, version);
            high = Math.max(high, version);
        }
        layers = new Layers(current.sorted(), current.buffer(), size);
        shape = new Shape(after, before.lineCount() + ids.size(), low, high);
        if (current.buffer().size() > Math.max(MIN_BUFFERED, current.sorted().size() / BUFFER_DIVISOR)) {
            mergeBuffer();
        }
    }
}
