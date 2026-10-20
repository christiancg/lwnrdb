package org.techhouse.bckg_ops;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.fs.CheckpointGuard;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

/**
 * A pending document's field-index entry is untrustworthy: index-backed reads must re-evaluate it
 * against the current document. Counts, not a set, so repeated writes to one id stay pending until
 * all of their index updates have completed.
 */
public class PendingIndexWrites {
    private static final Logger logger = Logger.logFor(PendingIndexWrites.class);
    private final Map<String, Map<String, Integer>> pending = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.locks.ReentrantLock> markerLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> generations = new ConcurrentHashMap<>();
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    private static final class PublishedSingletonGuard implements CheckpointGuard {
        @Override
        public boolean holdsIndexWork(String dbName, String collName) {
            return IocContainer.get(PendingIndexWrites.class).holdsIndexWork(dbName, collName);
        }

        @Override
        public Collection<String> pendingIds(String dbName, String collName) {
            return IocContainer.get(PendingIndexWrites.class).idsFor(dbName, collName);
        }
    }

    public PendingIndexWrites() {
        fs.guardCheckpoints(new PublishedSingletonGuard());
    }

    public long mark(String dbName, String collName, String id) {
        return mark(dbName, collName, List.of(id));
    }

    @FunctionalInterface
    public interface PendingWrite<T> {
        T write() throws Exception;
    }

    public <T> T writeWhilePending(String dbName, String collName, Iterable<String> ids, long generation,
            PendingWrite<T> write) throws Exception {
        try {
            return write.write();
        } catch (Exception e) {
            clear(dbName, collName, ids, generation);
            throw e;
        }
    }

    public long markRecovered(String dbName, String collName, Iterable<String> ids) {
        return mark(dbName, collName, ids, true);
    }

    public long mark(String dbName, String collName, Iterable<String> ids) {
        return mark(dbName, collName, ids, false);
    }

    public boolean holdsIndexWork(String dbName, String collName) {
        final var byId = pending.get(Cache.getCollectionIdentifier(dbName, collName));
        return byId != null && !byId.isEmpty();
    }

    private long mark(String dbName, String collName, Iterable<String> ids, boolean unlogged) {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var byId = pending.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        final var markerLock = markerLockFor(collId);
        markerLock.lock();
        try {
            for (var id : ids) {
                byId.merge(id, 1, Integer::sum);
            }
            if (unlogged) {
                markDirty(dbName, collName);
            }
            return generations.getOrDefault(collId, 0L);
        } finally {
            markerLock.unlock();
        }
    }

    private void markDirty(String dbName, String collName) {
        try {
            fs.indexMarkers().markDirty(dbName, collName);
        } catch (IOException e) {
            logger.warning("Could not make the index-dirty marker of " + dbName + "|" + collName + " durable while"
                    + " scheduling startup recovery; the ids stay pending in memory: " + e.getMessage());
        }
    }

    public void clear(String dbName, String collName, String id, long generation) {
        clearIn(dbName, collName, Cache.getCollectionIdentifier(dbName, collName), id, generation);
    }

    private void clearIn(String dbName, String collName, String collId, String id, long generation) {
        final var byId = pending.get(collId);
        if (byId == null) {
            return;
        }
        final var markerLock = markerLocks.computeIfAbsent(collId, _ -> new java.util.concurrent.locks.ReentrantLock());
        markerLock.lock();
        try {
            if (generation < generations.getOrDefault(collId, 0L)) {
                return;
            }
            byId.computeIfPresent(id, (_, current) -> current - 1 <= 0 ? null : current - 1);
            if (byId.isEmpty()) {
                fs.indexMarkers().clearDirty(dbName, collName);
            }
        } finally {
            markerLock.unlock();
        }
    }

    public void clear(String dbName, String collName, Iterable<String> ids, long generation) {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        for (var id : ids) {
            clearIn(dbName, collName, collId, id, generation);
        }
    }

    public void clearCollection(String dbName, String collName) {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var markerLock = markerLockFor(collId);
        markerLock.lock();
        try {
            generations.merge(collId, 1L, Long::sum);
            final var byId = pending.get(collId);
            if (byId != null) {
                byId.clear();
            }
        } finally {
            markerLock.unlock();
        }
    }

    public void clearDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        final var collIds = new HashSet<>(pending.keySet());
        collIds.addAll(generations.keySet());
        for (final var collId : collIds) {
            if (collId.startsWith(prefix)) {
                clearCollection(dbName, collId.substring(prefix.length()));
            }
        }
    }

    private java.util.concurrent.locks.ReentrantLock markerLockFor(String collId) {
        return markerLocks.computeIfAbsent(collId, _ -> new java.util.concurrent.locks.ReentrantLock());
    }

    public Set<String> idsFor(String dbName, String collName) {
        final var byId = pending.get(Cache.getCollectionIdentifier(dbName, collName));
        if (byId == null || byId.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(byId.keySet());
    }
}
