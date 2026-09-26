package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.OperationResponse;

public final class OperationLocks {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);

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

    private static boolean acquireWriteLock(String dbName, String collName, boolean bounded)
            throws InterruptedException {
        if (!bounded) {
            locks.lock(dbName, collName);
            return true;
        }
        return locks.tryLockWrite(dbName, collName, clusterConfig.replicationAckTimeoutMs());
    }

    public static OperationResponse withReadLocks(boolean dirtyRead, List<String> collectionIds, OperationType type,
            ErrorCode errorCode, OperationResponse.Attempt attempt) {
        return withReadLocks(dirtyRead, collectionIds, type, errorCode, false, attempt);
    }

    public static OperationResponse withReadLocks(boolean dirtyRead, List<String> collectionIds, OperationType type,
            ErrorCode errorCode, boolean bounded, OperationResponse.Attempt attempt) {
        final var readLocks = new ArrayList<String>();
        try {
            return OperationResponse.respondOrError(type, errorCode, () -> {
                final var acquired = bounded
                        ? locks.acquireReadLocks(dirtyRead, collectionIds,
                                Configuration.getInstance().getTransactionLockTimeoutMs())
                        : locks.acquireReadLocks(dirtyRead, collectionIds);
                if (acquired == null) {
                    return new OperationResponse(type, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
                }
                readLocks.addAll(acquired);
                return attempt.run();
            });
        } finally {
            locks.releaseReadLocks(readLocks);
        }
    }
}
