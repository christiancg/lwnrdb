package org.techhouse.concurrency;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class ResourceLocking {
    private static final Map<String, Semaphore> locks = new ConcurrentHashMap<>();

    public void lock(String dbName, String collName) throws InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        lock(collIdentifier);
    }

    private String getIndexIdentifier(String dbName, String collName, String fieldName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName;
    }

    public void lockIndex(String dbName, String collName, String fieldName) throws InterruptedException {
        lock(getIndexIdentifier(dbName, collName, fieldName));
    }

    public void releaseIndex(String dbName, String collName, String fieldName) {
        release(getIndexIdentifier(dbName, collName, fieldName));
    }

    private void lock(String lockName) throws InterruptedException {
        final var lock = locks.get(lockName);
        if (lock == null) {
            final var newLock = new Semaphore(1);
            locks.put(lockName, newLock);
            newLock.acquire();
        } else {
            lock.acquire();
        }
    }

    public void release(String lockName) {
        final var lock = locks.get(lockName);
        if (lock != null) {
            lock.release();
        }
    }

    public void release(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        release(collIdentifier);
    }

    public void removeLock(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        locks.remove(collIdentifier);
    }
}
