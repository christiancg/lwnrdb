package org.techhouse.cluster;

import org.techhouse.cache.Cache;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ScheduleOperationHelper;

public class MetadataCachePruner implements MembershipListener {
    private final Logger logger = Logger.logFor(MetadataCachePruner.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);

    @Override
    public void onMembershipChanged(MembershipView view) {
        try {
            cache.removeTriggersMatching(this::isNotOwned);
            cache.removeSchedulesMatching(this::isNotOwnedSchedule);
        } catch (Exception e) {
            logger.warning("Failed to prune the metadata caches after a membership change: " + e.getMessage());
        }
    }

    private boolean isNotOwnedSchedule(String scheduleIdentifier) {
        final var parts = scheduleIdentifier.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        if (parts.length < 2) {
            return false;
        }
        return !ownershipManager.isOwner(parts[0], ScheduleOperationHelper.ringKey(parts[1]));
    }

    private boolean isNotOwned(String collIdentifier) {
        final var parts = collIdentifier.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        if (parts.length < 2) {
            return false;
        }
        return !ownershipManager.isOwner(parts[0], parts[1]);
    }
}
