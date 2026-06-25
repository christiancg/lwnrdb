package org.techhouse.bckg_ops;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;

/**
 * Tracks documents that have been committed (document, PK index and document cache updated
 * synchronously) but whose asynchronous field-index update has not yet completed. Index
 * maintenance is eventually consistent, so during that window the field indexes do not yet reflect
 * the write; index-backed reads consult this overlay to stay consistent (a pending document's index
 * entry is untrustworthy, so readers re-evaluate it against the current document instead).
 *
 * <p>Counts (not a plain set) are kept per id so rapid repeated writes to the same id stay pending
 * until <em>all</em> their index updates have completed. All mutations use atomic
 * {@link ConcurrentHashMap} operations so {@code mark} and {@code clear} never lose updates.
 */
public class PendingIndexWrites {
    // db|coll -> (id -> pending-update count)
    private final Map<String, Map<String, Integer>> pending = new ConcurrentHashMap<>();

    public void mark(String dbName, String collName, String id) {
        pending.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName), _ -> new ConcurrentHashMap<>())
                .merge(id, 1, Integer::sum);
    }

    public void mark(String dbName, String collName, Iterable<String> ids) {
        for (var id : ids) {
            mark(dbName, collName, id);
        }
    }

    // Safe to call for an id that was never marked (e.g. DELETE events): computeIfPresent is a no-op
    // when the id is absent.
    public void clear(String dbName, String collName, String id) {
        final var byId = pending.get(Cache.getCollectionIdentifier(dbName, collName));
        if (byId != null) {
            byId.computeIfPresent(id, (_, current) -> current - 1 <= 0 ? null : current - 1);
        }
    }

    public void clear(String dbName, String collName, Iterable<String> ids) {
        for (var id : ids) {
            clear(dbName, collName, id);
        }
    }

    // Snapshot of the ids currently pending for the collection; empty when there are no recent
    // writes, in which case index reads incur no overhead.
    public Set<String> idsFor(String dbName, String collName) {
        final var byId = pending.get(Cache.getCollectionIdentifier(dbName, collName));
        if (byId == null || byId.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(byId.keySet());
    }
}
