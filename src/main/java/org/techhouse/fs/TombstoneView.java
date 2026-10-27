package org.techhouse.fs;

import java.util.Map;
import java.util.function.BiConsumer;

public final class TombstoneView {
    private final Map<String, Long> versions;
    private final long oldest;
    private final long highest;

    TombstoneView(Map<String, Long> versions, long oldest, long highest) {
        this.versions = versions;
        this.oldest = oldest;
        this.highest = highest;
    }

    public long versionOf(String id) {
        final var version = versions.get(id);
        return version == null ? 0L : version;
    }

    public void forEach(BiConsumer<String, Long> action) {
        versions.forEach(action);
    }

    public int size() {
        return versions.size();
    }

    public boolean isEmpty() {
        return versions.isEmpty();
    }

    public long oldest() {
        return oldest;
    }

    public long highest() {
        return highest;
    }
}
