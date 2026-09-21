package org.techhouse.ops;

import java.util.stream.Collectors;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class ReplicatedTxApplyHelper {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final Logger logger = Logger.logFor(ReplicatedTxApplyHelper.class);

    private ReplicatedTxApplyHelper() {
    }

    public static boolean apply(TxReplicationPayload payload) {
        return apply(payload, 0);
    }

    public static boolean apply(TxReplicationPayload payload, long timeoutMillis) {
        if (payload == null || payload.getEntries().isEmpty()) {
            return false;
        }
        final var collIds = payload.getEntries().stream()
                .map(entry -> Cache.getCollectionIdentifier(entry.getDbName(), entry.getCollName()))
                .collect(Collectors.toSet());
        try {
            return applyUnderWriteLocks(payload, collIds, timeoutMillis);
        } catch (Exception e) {
            logger.error("Failed to apply replicated transaction batch", e);
            return false;
        }
    }

    private static boolean applyUnderWriteLocks(TxReplicationPayload payload, java.util.Set<String> collIds,
            long timeoutMillis) throws Exception {
        final ResourceLocking.LockedAction<Boolean> applyEntries = () -> {
            for (final var entry : payload.getEntries()) {
                if (!ReplicatedApplyHelper.applyLocked(entry)) {
                    return false;
                }
            }
            return true;
        };
        return timeoutMillis > 0
                ? locks.withWriteLocks(collIds, timeoutMillis, applyEntries)
                : locks.withWriteLocks(collIds, applyEntries);
    }
}
