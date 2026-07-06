package org.techhouse.concurrency;

import java.util.ArrayList;
import java.util.List;
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

    // Bounded write-lock acquisition used by transactions: a transaction acquires each touched
    // collection's write lock lazily and holds it until commit/rollback, so it waits (up to the
    // timeout) for an in-flight write to finish rather than failing instantly, but gives up on the
    // timeout so two concurrent transactions can never deadlock (the caller aborts the transaction).
    public boolean tryLockWrite(String dbName, String collName, long timeoutMillis) throws InterruptedException {
        return lockFor(Cache.getCollectionIdentifier(dbName, collName)).writeLock().tryLock(timeoutMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ---------- collection read locking (shared) ----------
    public void lockRead(String dbName, String collName) throws InterruptedException {
        lockReadByName(Cache.getCollectionIdentifier(dbName, collName));
    }

    public void releaseRead(String dbName, String collName) {
        releaseReadByName(Cache.getCollectionIdentifier(dbName, collName));
    }

    // ---------- multi-resource read locking (shared) ----------
    // Acquire shared read locks on the given identifiers in a deterministic (sorted) order so two
    // overlapping multi-collection reads can never deadlock. Returns the identifiers actually locked
    // (in acquisition order); a dirty read takes no lock and returns an empty list, relying on
    // FileSystem's per-file locks for read validity.
    public List<String> acquireReadLocks(boolean dirtyRead, List<String> identifiers) throws InterruptedException {
        if (dirtyRead) {
            return List.of();
        }
        final var sorted = identifiers.stream().distinct().sorted().toList();
        final var acquired = new ArrayList<String>();
        for (var identifier : sorted) {
            lockReadByName(identifier);
            acquired.add(identifier);
        }
        return acquired;
    }

    public void releaseReadLocks(List<String> acquired) {
        for (var i = acquired.size() - 1; i >= 0; i--) {
            releaseReadByName(acquired.get(i));
        }
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
