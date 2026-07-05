package org.techhouse.analyze;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.config.Globals;

/**
 * Thread-scoped collector for AGGREGATE "analyze" mode. It is created and registered by
 * {@code OperationProcessor} only when the request opts in ({@code analyze=true}); the deep
 * aggregation helpers consult {@link #current()} and record into it, so the steady-state read path
 * is untouched when analyze is off ({@code current() == null}). The aggregation pipeline runs
 * sequentially on the connection's virtual thread, but the counters use concurrent primitives as a
 * safe default.
 */
public final class AnalyzeContext {
    private static final ThreadLocal<AnalyzeContext> CURRENT = new ThreadLocal<>();

    private final AtomicLong documentsScanned = new AtomicLong();
    private final Set<String> indexesUsed = ConcurrentHashMap.newKeySet();
    private final List<String> locksAcquired = new CopyOnWriteArrayList<>();

    public static AnalyzeContext current() {
        return CURRENT.get();
    }

    public static void set(AnalyzeContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    // Builds the field-index lock identifier (db|coll|field) in the same format ResourceLocking
    // uses, so a recorded lock matches the read lock actually taken to consult the field index.
    public static String fieldLockId(String dbName, String collName, String fieldName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName;
    }

    public void addScanned(long count) {
        documentsScanned.addAndGet(count);
    }

    public void addIndexUsed(String fieldName) {
        indexesUsed.add(fieldName);
    }

    public void addLock(String lockIdentifier) {
        locksAcquired.add(lockIdentifier);
    }

    public long getDocumentsScanned() {
        return documentsScanned.get();
    }

    public Set<String> getIndexesUsed() {
        return indexesUsed;
    }

    public List<String> getLocksAcquired() {
        return locksAcquired;
    }
}
