package org.techhouse.concurrency;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;

/**
 * The locks here are always acquired <em>above</em> the per-file locks held inside
 * {@code FileSystem}, never the other way around, or the two tiers deadlock.
 */
public class ResourceLocking {
    private static final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    private ReentrantReadWriteLock lockFor(String lockName) {
        return locks.computeIfAbsent(lockName, _ -> new ReentrantReadWriteLock());
    }

    public void lockWrite(String lockName) throws InterruptedException {
        lockFor(lockName).writeLock().lockInterruptibly();
    }

    public boolean holdsCollectionLock(String dbName, String collName) {
        final var lock = locks.get(Cache.getCollectionIdentifier(dbName, collName));
        return lock != null && (lock.getReadHoldCount() > 0 || lock.isWriteLockedByCurrentThread());
    }

    public boolean releaseWrite(String lockName) {
        final var lock = locks.get(lockName);
        if (lock != null && lock.isWriteLockedByCurrentThread()) {
            lock.writeLock().unlock();
            return true;
        }
        return false;
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

    public void lock(String dbName, String collName) throws InterruptedException {
        lockWrite(Cache.getCollectionIdentifier(dbName, collName));
    }

    public void release(String dbName, String collName) {
        releaseWrite(dbName, collName);
    }

    public boolean releaseWrite(String dbName, String collName) {
        return releaseWrite(Cache.getCollectionIdentifier(dbName, collName));
    }

    public boolean tryLockWrite(String dbName, String collName) {
        return lockFor(Cache.getCollectionIdentifier(dbName, collName)).writeLock().tryLock();
    }

    public interface LockedAction<T> {
        T run() throws Exception;
    }

    // Sorted order, so two callers with overlapping sets cannot deadlock. Write locks are thread-owned:
    // the action must complete on this thread, or releaseWrite silently no-ops and strands the lock.
    public <T> T withWriteLocks(Collection<String> collectionIds, LockedAction<T> action) throws Exception {
        final var acquired = new ArrayList<String>();
        try {
            for (final var collId : new TreeSet<>(collectionIds)) {
                lockWrite(collId);
                acquired.add(collId);
            }
            return action.run();
        } finally {
            for (final var collId : acquired) {
                releaseWrite(collId);
            }
        }
    }

    // Transactions hold their write locks until commit/rollback; the timeout is what stops two of
    // them deadlocking, by making the caller abort instead of waiting forever.
    public boolean tryLockWrite(String dbName, String collName, long timeoutMillis) throws InterruptedException {
        return lockFor(Cache.getCollectionIdentifier(dbName, collName)).writeLock().tryLock(timeoutMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void lockRead(String dbName, String collName) throws InterruptedException {
        lockReadByName(Cache.getCollectionIdentifier(dbName, collName));
    }

    public boolean tryLockRead(String dbName, String collName, long timeoutMillis) throws InterruptedException {
        return lockFor(Cache.getCollectionIdentifier(dbName, collName)).readLock().tryLock(timeoutMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void releaseRead(String dbName, String collName) {
        releaseReadByName(Cache.getCollectionIdentifier(dbName, collName));
    }

    // Sorted order, so two overlapping multi-collection reads cannot deadlock. A dirty read takes no
    // lock at all and relies on FileSystem's per-file locks for read validity.
    public List<String> acquireReadLocks(boolean dirtyRead, List<String> identifiers) throws InterruptedException {
        if (dirtyRead) {
            return List.of();
        }
        final var sorted = identifiers.stream().distinct().sorted().toList();
        final var acquired = new ArrayList<String>();
        try {
            for (var identifier : sorted) {
                lockReadByName(identifier);
                acquired.add(identifier);
            }
        } catch (InterruptedException e) {
            // The caller only releases the returned list, so a partial acquisition must undo itself.
            releaseReadLocks(acquired);
            throw e;
        }
        return acquired;
    }

    public List<String> acquireReadLocks(boolean dirtyRead, List<String> identifiers, long timeoutMillis)
            throws InterruptedException {
        if (dirtyRead) {
            return List.of();
        }
        final var sorted = identifiers.stream().distinct().sorted().toList();
        final var acquired = new ArrayList<String>();
        try {
            for (var identifier : sorted) {
                if (!lockFor(identifier).readLock().tryLock(timeoutMillis,
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    releaseReadLocks(acquired);
                    return null;
                }
                acquired.add(identifier);
            }
        } catch (InterruptedException e) {
            releaseReadLocks(acquired);
            throw e;
        }
        return acquired;
    }

    public void releaseReadLocks(List<String> acquired) {
        for (var i = acquired.size() - 1; i >= 0; i--) {
            releaseReadByName(acquired.get(i));
        }
    }

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
        locks.computeIfPresent(collIdentifier, (_, lock) -> isEvictable(lock) ? null : lock);
        final var indexPrefix = collIdentifier + Globals.COLL_IDENTIFIER_SEPARATOR;
        for (final var key : List.copyOf(locks.keySet())) {
            if (key.startsWith(indexPrefix)) {
                locks.computeIfPresent(key, (_, lock) -> isEvictable(lock) ? null : lock);
            }
        }
    }

    private static boolean isEvictable(ReentrantReadWriteLock lock) {
        return !lock.isWriteLocked() && lock.getReadLockCount() == 0 && !lock.hasQueuedThreads();
    }
}
