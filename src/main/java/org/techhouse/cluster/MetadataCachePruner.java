package org.techhouse.cluster;

import org.techhouse.cache.Cache;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

/**
 * Drops the cached trigger lists of collections this node no longer owns, so the trigger cache is
 * partitioned across the cluster rather than duplicated on every node.
 *
 * <p>
 * Safe because a trigger only ever fires on its collection's owner: {@code ops.TriggerHelper.afterWrite} is
 * called from OperationProcessor's write handlers, and writes route to the owner. Registered immediately
 * after {@link OwnershipManager} in {@code Main.startClusterIfEnabled}, since listeners are notified in
 * registration order and this one must read the rebuilt ring.
 */
public class MetadataCachePruner implements MembershipListener {
    private final Logger logger = Logger.logFor(MetadataCachePruner.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);

    @Override
    public void onMembershipChanged(MembershipView view) {
        try {
            cache.removeTriggersMatching(this::isNotOwned);
        } catch (Exception e) {
            logger.warning("Failed to prune the trigger cache after a membership change: " + e.getMessage());
        }
    }

    private boolean isNotOwned(String collIdentifier) {
        final var parts = collIdentifier.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        if (parts.length < 2) {
            return false;
        }
        return !ownershipManager.isOwner(parts[0], parts[1]);
    }
}
