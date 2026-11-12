package org.techhouse.ops.snapshot;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.techhouse.fs.SnapshotImageFile;

public final class Snapshot {
    private final String id;
    private final String dbName;
    private final UUID owner;
    private final List<String> collectionNames;
    private final Set<String> collections;
    private final Map<String, CollectionImages> images = new ConcurrentHashMap<>();
    private final AtomicLong charged = new AtomicLong();
    private volatile long lastUsedNanos;
    private volatile String abortReason;

    Snapshot(String id, String dbName, UUID owner, List<String> collectionNames, long nowNanos) {
        this.id = id;
        this.dbName = dbName;
        this.owner = owner;
        this.collectionNames = List.copyOf(collectionNames);
        this.collections = Set.copyOf(collectionNames);
        this.lastUsedNanos = nowNanos;
    }

    public String id() {
        return id;
    }

    public String dbName() {
        return dbName;
    }

    UUID owner() {
        return owner;
    }

    public List<String> collectionNames() {
        return collectionNames;
    }

    public boolean holds(String collName) {
        return collections.contains(collName);
    }

    public boolean isAborted() {
        return abortReason != null;
    }

    public String abortReason() {
        return abortReason;
    }

    long lastUsedNanos() {
        return lastUsedNanos;
    }

    void touch(long nowNanos) {
        lastUsedNanos = nowNanos;
    }

    long charge(long bytes) {
        return charged.addAndGet(bytes);
    }

    synchronized boolean abort(String reason) {
        if (abortReason != null) {
            return false;
        }
        abortReason = reason;
        return true;
    }

    CollectionImages imagesOf(String collName, Function<String, SnapshotImageFile> files) {
        return images.computeIfAbsent(collName, name -> new CollectionImages(files.apply(name)));
    }

    CollectionImages existingImagesOf(String collName) {
        return images.get(collName);
    }

    void closeImages() throws IOException {
        IOException failure = null;
        for (final var collectionImages : images.values()) {
            try {
                collectionImages.closeFile();
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
