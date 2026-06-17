package org.techhouse.concurrency;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;

/**
 * Per-resource read/write locking. Each collection (keyed {@code db|coll}) and each field index
 * (keyed {@code db|coll|field}) gets a {@link ReentrantReadWriteLock}: readers share, writers are
 * exclusive. While a writer holds a resource, nobody else may read or write it; multiple readers
 * may proceed concurrently.
 *
 * <p>Lock-ordering rule: the collection/index locks managed here are always acquired <em>above</em>
 * the per-file locks held inside {@code FileSystem}, never the other way around, so the two tiers
 * cannot deadlock.
 */
public class ResourceLocking {
    private static final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    private ReentrantReadWriteLock lockFor(String lockName) {
        return locks.computeIfAbsent(lockName, _ -> new ReentrantReadWriteLock());
    }

    // ---------- name-based primitives ----------
    public void lockWrite(String lockName) throws InterruptedException {
        lockFor(lockName).writeLock().lockInterruptibly();
    }

    public void releaseWrite(String lockName) {
        final var lock = locks.get(lockName);
        if (lock != null && lock.isWriteLockedByCurrentThread()) {
            lock.writeLock().unlock();
        }
    }

    public void lockReadByName(String lockName) throws InterruptedException {
        lockFor(lockName).readLock().lockInterruptibly();
    }

    public void releaseReadByName(String lockName) {
        final var lock = locks.get(lockName);
        if (lock != null && lock.getReadHoldCount() > 0) {
            lock.readLock().unlock();
        }
    }

    // ---------- collection write locking (exclusive) ----------
    public void lock(String dbName, String collName) throws InterruptedException {
        lockWrite(Cache.getCollectionIdentifier(dbName, collName));
    }

    public void release(String dbName, String collName) {
        releaseWrite(dbName, collName);
    }

    public void releaseWrite(String dbName, String collName) {
        releaseWrite(Cache.getCollectionIdentifier(dbName, collName));
    }

    public boolean tryLockWrite(String dbName, String collName) {
        return lockFor(Cache.getCollectionIdentifier(dbName, collName)).writeLock().tryLock();
    }

    // ---------- collection read locking (shared) ----------
    public void lockRead(String dbName, String collName) throws InterruptedException {
        lockReadByName(Cache.getCollectionIdentifier(dbName, collName));
    }

    public void releaseRead(String dbName, String collName) {
        releaseReadByName(Cache.getCollectionIdentifier(dbName, collName));
    }

    // ---------- field index locking ----------
    private String getIndexIdentifier(String dbName, String collName, String fieldName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName;
    }

    public void lockIndex(String dbName, String collName, String fieldName) throws InterruptedException {
        lockWrite(getIndexIdentifier(dbName, collName, fieldName));
    }

    public void releaseIndex(String dbName, String collName, String fieldName) {
        releaseWrite(getIndexIdentifier(dbName, collName, fieldName));
    }

    public void lockIndexRead(String dbName, String collName, String fieldName) throws InterruptedException {
        lockReadByName(getIndexIdentifier(dbName, collName, fieldName));
    }

    public void releaseIndexRead(String dbName, String collName, String fieldName) {
        releaseReadByName(getIndexIdentifier(dbName, collName, fieldName));
    }

    public void removeLock(String dbName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        locks.remove(collIdentifier);
    }
}
