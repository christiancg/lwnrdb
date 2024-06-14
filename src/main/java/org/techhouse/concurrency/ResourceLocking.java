package org.techhouse.concurrency;

import org.techhouse.cache.Cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class ResourceLocking {
    private static final Map<String, Semaphore> locks = new ConcurrentHashMap<>();

    public void lock(String dbName, String collName) throws InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var lock = locks.get(collIdentifier);
        if (lock == null) {
            final var newLock = new Semaphore(1);
            locks.put(collIdentifier, newLock);
            newLock.acquire();
        } else {
            lock.acquire();
        }
    }

    public void release(String collIdentifier) {
        final var lock = locks.get(collIdentifier);
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
