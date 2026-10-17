package org.techhouse.analyze;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.config.Globals;

public final class AnalyzeContext {
    private final Set<String> indexesUsed = new HashSet<>();
    private final List<String> locksAcquired = new ArrayList<>();
    private long documentsScanned;
    private long scriptInvocations;
    private long scriptNanos;

    public static String fieldLockId(String dbName, String collName, String fieldName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName;
    }

    public void addScanned(long count) {
        documentsScanned += count;
    }

    public void addIndexUsed(String fieldName) {
        indexesUsed.add(fieldName);
    }

    public void addLock(String lockIdentifier) {
        locksAcquired.add(lockIdentifier);
    }

    public void recordScriptInvocation(long nanos) {
        scriptInvocations++;
        scriptNanos += nanos;
    }

    public long getScriptInvocations() {
        return scriptInvocations;
    }

    public long getScriptMillis() {
        return scriptNanos / 1_000_000L;
    }

    public long getDocumentsScanned() {
        return documentsScanned;
    }

    public Set<String> getIndexesUsed() {
        return indexesUsed;
    }

    public List<String> getLocksAcquired() {
        return locksAcquired;
    }
}
