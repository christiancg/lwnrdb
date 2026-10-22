package org.techhouse.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class UsageTracker {
    private final Logger logger = Logger.logFor(UsageTracker.class);
    private final Configuration config = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ConcurrentHashMap<String, UsageCounter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AccessKey, UsageCounter> byAccess = new ConcurrentHashMap<>();
    private final Set<UsageCounter> unflushed = ConcurrentHashMap.newKeySet();

    private record AccessKey(AccessKind kind, String dbName, String collName, String indexKey) {
    }

    private boolean isCachingDisabled() {
        return config.isCachingDisabled();
    }

    public static String buildKey(AccessKind kind, String dbName, String collName, String indexKey) {
        return kind.name() + Globals.COLL_IDENTIFIER_SEPARATOR + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                + Globals.COLL_IDENTIFIER_SEPARATOR + (indexKey == null ? "" : indexKey);
    }

    public void recordAccess(AccessKind kind, String dbName, String collName, String indexKey) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        if (isCachingDisabled()) {
            return;
        }
        final var counter = counterOf(kind, dbName, collName, indexKey);
        counter.increment(System.currentTimeMillis());
        if (counter.markUnflushed()) {
            unflushed.add(counter);
        }
    }

    private UsageCounter counterOf(AccessKind kind, String dbName, String collName, String indexKey) {
        final var access = accessKeyOf(kind, dbName, collName, indexKey);
        final var known = byAccess.get(access);
        if (known != null) {
            return known;
        }
        return byAccess.computeIfAbsent(access,
                _ -> counters.computeIfAbsent(buildKey(kind, dbName, collName, indexKey),
                        _ -> new UsageCounter(kind, dbName, collName, access.indexKey(), 0L, 0L)));
    }

    private static AccessKey accessKeyOf(AccessKind kind, String dbName, String collName, String indexKey) {
        return new AccessKey(kind, dbName, collName, indexKey == null ? "" : indexKey);
    }

    public List<UsageCounter> drainDirtyCounters() {
        if (unflushed.isEmpty()) {
            return List.of();
        }
        final var drained = new ArrayList<UsageCounter>();
        for (final var counter : List.copyOf(unflushed)) {
            unflushed.remove(counter);
            counter.clearUnflushed();
            final var key = buildKey(counter.kind(), counter.dbName(), counter.collName(), counter.indexKey());
            if (counters.get(key) == counter) {
                drained.add(counter);
            }
        }
        return drained;
    }

    public UsageCounter getCounter(AccessKind kind, String dbName, String collName, String indexKey) {
        return counters.get(buildKey(kind, dbName, collName, indexKey));
    }

    public Map<String, UsageCounter> getCountersSnapshot() {
        return Map.copyOf(counters);
    }

    public void clearCounter(AccessKind kind, String dbName, String collName, String indexKey) {
        counters.remove(buildKey(kind, dbName, collName, indexKey));
        byAccess.remove(accessKeyOf(kind, dbName, collName, indexKey));
    }

    public void putCounter(UsageCounter counter) {
        counters.put(buildKey(counter.kind(), counter.dbName(), counter.collName(), counter.indexKey()), counter);
        byAccess.remove(accessKeyOf(counter.kind(), counter.dbName(), counter.collName(), counter.indexKey()));
    }

    public void loadProfileFromAdmin() {
        if (isCachingDisabled()) {
            return;
        }
        try {
            try (var pagesStream = fs.streamPages(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME)) {
                pagesStream.forEach(map -> {
                    for (var e : map.values()) {
                        try {
                            final var data = e.getData();
                            data.addProperty(Globals.PK_FIELD, e.get_id());
                            final var usage = AdminCollectionUsageEntry.fromJsonObject(data);
                            final var counter = new UsageCounter(usage.getKind(), usage.getDbName(),
                                    usage.getCollName(), usage.getIndexKey(), usage.getAccessCount(),
                                    usage.getLastAccessMillis());
                            putCounter(counter);
                        } catch (Exception inner) {
                            logger.warning("Skipping malformed collection_usage entry '" + e.get_id() + "': "
                                    + inner.getMessage());
                        }
                    }
                });
            }
        } catch (Exception e) {
            logger.warning(
                    "Failed to load collection usage profile, continuing with empty counters: " + e.getMessage());
        }
    }

    long accessCountFor(CacheableResource r) {
        final var counter = counterFor(r);
        return counter == null ? 0L : counter.getAccessCount();
    }

    long lastAccessFor(CacheableResource r) {
        final var counter = counterFor(r);
        return counter == null ? 0L : counter.getLastAccessMillis();
    }

    private UsageCounter counterFor(CacheableResource r) {
        return counters.get(buildKey(r.kind(), r.dbName(), r.collName(), countedIndexKey(r)));
    }

    private static String countedIndexKey(CacheableResource r) {
        if (r.kind() != AccessKind.FIELD_INDEX || r.indexKey() == null) {
            return r.indexKey();
        }
        final var separator = r.indexKey().lastIndexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        return separator < 0 ? r.indexKey() : r.indexKey().substring(0, separator);
    }
}
