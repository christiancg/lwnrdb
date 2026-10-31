package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.OperationResponse;

public final class OperationLocks {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final Configuration configuration = Configuration.getInstance();

    private OperationLocks() {
    }

    public static OperationResponse withCollectionLock(String dbName, String collName, OperationType type,
            ErrorCode errorCode, OperationResponse.Attempt attempt) {
        return withCollectionLock(dbName, collName, type, errorCode, false, attempt);
    }

    public static OperationResponse withCollectionLock(String dbName, String collName, OperationType type,
            ErrorCode errorCode, boolean bounded, OperationResponse.Attempt attempt) {
        try {
            return OperationResponse.respondOrError(type, errorCode, () -> {
                if (!acquireWriteLock(dbName, collName, bounded)) {
                    return new OperationResponse(type, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
                }
                return attempt.run();
            });
        } finally {
            locks.release(dbName, collName);
        }
    }

    static OperationResponse withGroupedCollectionLock(String dbName, String collName, OperationType type,
            ErrorCode errorCode, GroupCommit.Eligibility eligibility, GroupCommit.GroupedAttempt attempt) {
        if (!GroupCommit.enabled() || !GroupCommit.groupable(eligibility.groupable())) {
            return withCollectionLock(dbName, collName, type, errorCode,
                    () -> attempt.prepare(WriteEffects.IMMEDIATE).commit());
        }
        return GroupCommit.run(dbName, collName, new GroupCommit.GroupedWrite(type, errorCode, eligibility, attempt));
    }

    private static boolean acquireWriteLock(String dbName, String collName, boolean bounded)
            throws InterruptedException {
        if (!bounded) {
            locks.lock(dbName, collName);
            return true;
        }
        return locks.tryLockWrite(dbName, collName, clusterConfig.replicationAckTimeoutMs());
    }

    public static long lockBudgetMillis(boolean bounded) {
        return bounded ? clusterConfig.replicationAckTimeoutMs() : configuration.getTransactionLockTimeoutMs();
    }

    public static OperationResponse withDatabaseShared(String dbName, OperationType type, ErrorCode errorCode,
            OperationResponse.Attempt attempt) {
        final var held = new AtomicBoolean();
        try {
            return OperationResponse.respondOrError(type, errorCode, () -> {
                if (!locks.tryLockDatabaseShared(dbName, lockBudgetMillis(false))) {
                    return new OperationResponse(type, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
                }
                held.set(true);
                return attempt.run();
            });
        } finally {
            if (held.get()) {
                locks.releaseDatabaseShared(dbName);
            }
        }
    }

    public static OperationResponse withReadLocks(boolean dirtyRead, List<String> collectionIds, OperationType type,
            ErrorCode errorCode, OperationResponse.Attempt attempt) {
        return withReadLocks(dirtyRead, collectionIds, type, errorCode, false, attempt);
    }

    public static OperationResponse withReadLocks(boolean dirtyRead, List<String> collectionIds, OperationType type,
            ErrorCode errorCode, boolean bounded, OperationResponse.Attempt attempt) {
        return withAcquiredReadLocks(timeout -> locks.acquireReadLocks(dirtyRead, collectionIds, timeout),
                locks::releaseReadLocks, type, errorCode, bounded, attempt);
    }

    public static OperationResponse withPointReadLock(boolean dirtyRead, String collectionId, OperationType type,
            ErrorCode errorCode, boolean bounded, OperationResponse.Attempt attempt) {
        return withAcquiredReadLocks(timeout -> locks.acquirePointReadLock(dirtyRead, collectionId, timeout),
                locks::releasePointReadLocks, type, errorCode, bounded, attempt);
    }

    private interface ReadLockAcquisition {
        List<String> acquire(long timeoutMillis) throws InterruptedException;
    }

    private static OperationResponse withAcquiredReadLocks(ReadLockAcquisition acquisition,
            Consumer<List<String>> release, OperationType type, ErrorCode errorCode, boolean bounded,
            OperationResponse.Attempt attempt) {
        final var readLocks = new ArrayList<String>();
        try {
            return OperationResponse.respondOrError(type, errorCode, () -> {
                final var acquired = acquisition.acquire(bounded ? configuration.getTransactionLockTimeoutMs() : 0L);
                if (acquired == null) {
                    return new OperationResponse(type, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
                }
                readLocks.addAll(acquired);
                return attempt.run();
            });
        } finally {
            release.accept(readLocks);
        }
    }
}
