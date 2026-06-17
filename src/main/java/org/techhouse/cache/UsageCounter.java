package org.techhouse.cache;

import java.util.concurrent.atomic.AtomicLong;

public class UsageCounter {
    private final AccessKind kind;
    private final String dbName;
    private final String collName;
    private final String indexKey;
    private final AtomicLong accessCount;
    private final AtomicLong lastAccessMillis;

    public UsageCounter(AccessKind kind, String dbName, String collName, String indexKey,
                        long initialAccessCount, long initialLastAccessMillis) {
        this.kind = kind;
        this.dbName = dbName;
        this.collName = collName;
        this.indexKey = indexKey == null ? "" : indexKey;
        this.accessCount = new AtomicLong(initialAccessCount);
        this.lastAccessMillis = new AtomicLong(initialLastAccessMillis);
    }

    public void increment(long timestampMillis) {
        accessCount.incrementAndGet();
        lastAccessMillis.set(timestampMillis);
    }

    public AccessKind kind() {
        return kind;
    }

    public String dbName() {
        return dbName;
    }

    public String collName() {
        return collName;
    }

    public String indexKey() {
        return indexKey;
    }

    public long getAccessCount() {
        return accessCount.get();
    }

    public long getLastAccessMillis() {
        return lastAccessMillis.get();
    }
}
