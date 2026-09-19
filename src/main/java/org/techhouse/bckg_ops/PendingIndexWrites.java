package org.techhouse.bckg_ops;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

/**
 * A pending document's field-index entry is untrustworthy: index-backed reads must re-evaluate it
 * against the current document. Counts, not a set, so repeated writes to one id stay pending until
 * all of their index updates have completed.
 */
public class PendingIndexWrites {
    private final Map<String, Map<String, Integer>> pending = new ConcurrentHashMap<>();
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    public void mark(String dbName, String collName, String id) {
        final var byId = pending.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName),
                _ -> new ConcurrentHashMap<>());
        final var wasEmpty = byId.isEmpty();
        byId.merge(id, 1, Integer::sum);
        if (wasEmpty) {
            fs.markIndexesDirty(dbName, collName);
        }
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
            if (byId.isEmpty()) {
                fs.clearIndexesDirty(dbName, collName);
            }
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
