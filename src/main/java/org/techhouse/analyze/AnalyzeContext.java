package org.techhouse.analyze;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.config.Globals;

/**
 * Safe as a ThreadLocal because an aggregation pipeline runs sequentially on the connection's virtual thread.
 */
public final class AnalyzeContext {
    private static final ThreadLocal<AnalyzeContext> CURRENT = new ThreadLocal<>();

    private final AtomicLong documentsScanned = new AtomicLong();
    private final Set<String> indexesUsed = ConcurrentHashMap.newKeySet();
    private final List<String> locksAcquired = new CopyOnWriteArrayList<>();
    private final AtomicLong scriptInvocations = new AtomicLong();
    private final AtomicLong scriptNanos = new AtomicLong();

    public static AnalyzeContext current() {
        return CURRENT.get();
    }

    public static void set(AnalyzeContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

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

    public void recordScriptInvocation(long nanos) {
        scriptInvocations.incrementAndGet();
        scriptNanos.addAndGet(nanos);
    }

    public long getScriptInvocations() {
        return scriptInvocations.get();
    }

    public long getScriptMillis() {
        return scriptNanos.get() / 1_000_000L;
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
