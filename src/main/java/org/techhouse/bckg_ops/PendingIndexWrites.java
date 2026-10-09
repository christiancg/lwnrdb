package org.techhouse.bckg_ops;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

/**
 * A pending document's field-index entry is untrustworthy: index-backed reads must re-evaluate it
 * against the current document. Counts, not a set, so repeated writes to one id stay pending until
 * all of their index updates have completed.
 */
public class PendingIndexWrites {
    private final Map<String, Map<String, Integer>> pending = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.locks.ReentrantLock> markerLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> generations = new ConcurrentHashMap<>();
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    public long mark(String dbName, String collName, String id) {
        final var byId = pending.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName),
                _ -> new ConcurrentHashMap<>());
        final var markerLock = markerLockFor(dbName, collName);
        markerLock.lock();
        try {
            final var wasEmpty = byId.isEmpty();
            byId.merge(id, 1, Integer::sum);
            if (wasEmpty) {
                fs.markIndexesDirty(dbName, collName);
            }
            return currentGeneration(dbName, collName);
        } finally {
            markerLock.unlock();
        }
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

    public long mark(String dbName, String collName, Iterable<String> ids) {
        var generation = currentGeneration(dbName, collName);
        for (var id : ids) {
            generation = mark(dbName, collName, id);
        }
        return generation;
    }

    public void clear(String dbName, String collName, String id, long generation) {
        final var byId = pending.get(Cache.getCollectionIdentifier(dbName, collName));
        if (byId == null) {
            return;
        }
        final var markerLock = markerLockFor(dbName, collName);
        markerLock.lock();
        try {
            if (generation < currentGeneration(dbName, collName)) {
                return;
            }
            byId.computeIfPresent(id, (_, current) -> current - 1 <= 0 ? null : current - 1);
            if (byId.isEmpty()) {
                fs.clearIndexesDirty(dbName, collName);
            }
        } finally {
            markerLock.unlock();
        }
    }

    public void clear(String dbName, String collName, Iterable<String> ids, long generation) {
        for (var id : ids) {
            clear(dbName, collName, id, generation);
        }
    }

    public void clearCollection(String dbName, String collName) {
        final var markerLock = markerLockFor(dbName, collName);
        markerLock.lock();
        try {
            generations.merge(Cache.getCollectionIdentifier(dbName, collName), 1L, Long::sum);
            final var byId = pending.get(Cache.getCollectionIdentifier(dbName, collName));
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

    private long currentGeneration(String dbName, String collName) {
        return generations.getOrDefault(Cache.getCollectionIdentifier(dbName, collName), 0L);
    }

    private java.util.concurrent.locks.ReentrantLock markerLockFor(String dbName, String collName) {
        return markerLocks.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName),
                _ -> new java.util.concurrent.locks.ReentrantLock());
    }

    public Set<String> idsFor(String dbName, String collName) {
        final var byId = pending.get(Cache.getCollectionIdentifier(dbName, collName));
        if (byId == null || byId.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(byId.keySet());
    }
}
