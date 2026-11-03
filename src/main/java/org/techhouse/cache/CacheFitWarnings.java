package org.techhouse.cache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

final class CacheFitWarnings {
    private static final Logger logger = Logger.logFor(CacheFitWarnings.class);

    static final long CROWDED_OUT_INTERVAL_NANOS = TimeUnit.HOURS.toNanos(1);

    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> crowdedOutAt = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    CacheFitWarnings() {
        this(System::nanoTime);
    }

    CacheFitWarnings(LongSupplier clock) {
        this.clock = clock;
    }

    void pkIndexRefused(String dbName, String collName, long bytes, long maxBytes, long footprintBytes) {
        if (bytes > maxBytes) {
            pkIndexOutgrows(dbName, collName, bytes, maxBytes);
        } else if (claimCrowdedOut(Cache.getCollectionIdentifier(dbName, collName))) {
            logger.warning("No room in maxMemory for the primary-key index of " + dbName + "|" + collName
                    + ": it needs about " + bytes + " bytes and " + free(maxBytes, footprintBytes)
                    + " are free (maxMemory " + maxBytes + "), so keyed reads and writes scan pk.idx for the ids"
                    + " they name until other cached structures leave room.");
        }
    }

    void fieldIndexRefused(String dbName, String collName, String indexKey, long bytes, long maxBytes,
            long footprintBytes) {
        if (bytes > maxBytes) {
            fieldIndexOutgrows(dbName, collName, indexKey, bytes, maxBytes);
        } else if (claimCrowdedOut(
                Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + indexKey)) {
            logger.warning("No room in maxMemory for the field index " + indexKey + " of " + dbName + "|" + collName
                    + ": it needs about " + bytes + " bytes and " + free(maxBytes, footprintBytes)
                    + " are free (maxMemory " + maxBytes + "), so it is not cached while other indexes and documents"
                    + " hold the budget, and every read of it loads it from disk.");
        }
    }

    private static long free(long maxBytes, long footprintBytes) {
        return Math.max(0L, maxBytes - footprintBytes);
    }

    private boolean claimCrowdedOut(String key) {
        final var now = clock.getAsLong();
        final var last = crowdedOutAt.get(key);
        if (last != null && now - last < CROWDED_OUT_INTERVAL_NANOS) {
            return false;
        }
        return last == null ? crowdedOutAt.putIfAbsent(key, now) == null : crowdedOutAt.replace(key, last, now);
    }

    void pkIndexOutgrows(String dbName, String collName, long bytes, long maxBytes) {
        if (bytes > maxBytes && warned.add(Cache.getCollectionIdentifier(dbName, collName))) {
            logger.warning("The primary-key index of " + dbName + "|" + collName + " needs about " + bytes
                    + " bytes of cache, more than maxMemory (" + maxBytes + "), so it is never cached: keyed reads"
                    + " and writes scan pk.idx for the ids they name. Raise maxMemory above " + bytes
                    + " to keep it resident.");
        }
    }

    void fieldIndexOutgrows(String dbName, String collName, String indexKey, long bytes, long maxBytes) {
        final var key = Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + indexKey;
        if (bytes > maxBytes && warned.add(key)) {
            logger.warning("The field index " + indexKey + " of " + dbName + "|" + collName + " needs about " + bytes
                    + " bytes of cache, more than maxMemory (" + maxBytes + "), so it is never cached: every read"
                    + " of it loads it from disk and writes append to its log. Raise maxMemory above " + bytes
                    + " to keep it resident.");
        }
    }

    void forgetCollection(String collectionIdentifier) {
        final var prefix = collectionIdentifier + Globals.COLL_IDENTIFIER_SEPARATOR;
        warned.removeIf(key -> key.equals(collectionIdentifier) || key.startsWith(prefix));
        crowdedOutAt.keySet().removeIf(key -> key.equals(collectionIdentifier) || key.startsWith(prefix));
    }

    void forgetByPrefix(String prefix) {
        warned.removeIf(key -> key.startsWith(prefix));
        crowdedOutAt.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
