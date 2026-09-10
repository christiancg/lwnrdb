package org.techhouse.ops;

import java.util.stream.Collectors;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

/**
 * Applies a replicated transaction (a {@link TxReplicationPayload}) onto this replica as one atomic batch:
 * every collection the batch touches is write-locked up front (in a stable, sorted order to stay
 * deadlock-safe), then each entry is applied within that single window so no other writer interleaves
 * mid-transaction. A mid-batch failure NACKs; the owner's local commit stands and anti-entropy reconciles.
 */
public final class ReplicatedTxApplyHelper {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final Logger logger = Logger.logFor(ReplicatedTxApplyHelper.class);

    private ReplicatedTxApplyHelper() {
    }

    public static boolean apply(TxReplicationPayload payload) {
        if (payload == null || payload.getEntries().isEmpty()) {
            return false;
        }
        final var collIds = payload.getEntries().stream()
                .map(entry -> Cache.getCollectionIdentifier(entry.getDbName(), entry.getCollName()))
                .collect(Collectors.toSet());
        try {
            return locks.withWriteLocks(collIds, () -> {
                for (final var entry : payload.getEntries()) {
                    if (!ReplicatedApplyHelper.applyLocked(entry)) {
                        return false;
                    }
                }
                return true;
            });
        } catch (Exception e) {
            logger.error("Failed to apply replicated transaction batch", e);
            return false;
        }
    }
}
