package org.techhouse.bckg_ops;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;

/**
 * A pending document's field-index entry is untrustworthy: index-backed reads must re-evaluate it
 * against the current document. Counts, not a set, so repeated writes to one id stay pending until
 * all of their index updates have completed.
 */
public class PendingIndexWrites {
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

    public Set<String> idsFor(String dbName, String collName) {
        final var byId = pending.get(Cache.getCollectionIdentifier(dbName, collName));
        if (byId == null || byId.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(byId.keySet());
    }
}
