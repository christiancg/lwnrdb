package org.techhouse.ops;

import java.util.ArrayList;
import java.util.TreeSet;
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
        final var collIds = new TreeSet<String>();
        for (final var entry : payload.getEntries()) {
            collIds.add(Cache.getCollectionIdentifier(entry.getDbName(), entry.getCollName()));
        }
        final var locked = new ArrayList<String>();
        try {
            for (final var collId : collIds) {
                locks.lockWrite(collId);
                locked.add(collId);
            }
            for (final var entry : payload.getEntries()) {
                if (!ReplicatedApplyHelper.applyLocked(entry)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to apply replicated transaction batch", e);
            return false;
        } finally {
            for (final var collId : locked) {
                locks.releaseWrite(collId);
            }
        }
    }
}
