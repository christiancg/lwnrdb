package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

/**
 * Write-path facade the operation layer consults when clustering is enabled: it decides whether this node
 * may coordinate a write (ownership + quorum) and, after the local commit, replicates it to a majority.
 */
public class ClusterCoordinator {
    private final Logger logger = Logger.logFor(ClusterCoordinator.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final Replicator replicator = IocContainer.get(Replicator.class);
    private final Cache cache = IocContainer.get(Cache.class);

    public WriteGuard guardWrite(String dbName, String collName) {
        if (doesntCoordinate(dbName)) {
            return WriteGuard.allow();
        }
        if (!ownershipManager.hasQuorum()) {
            return WriteGuard.noQuorum();
        }
        if (!ownershipManager.isOwner(dbName, collName)) {
            return WriteGuard.notOwner(ownershipManager.ownerAddress(dbName, collName));
        }
        return WriteGuard.allow();
    }

    public ReplicationOutcome replicateUpsert(String dbName, String collName, List<String> ids) {
        if (notApplicable(dbName, collName)) {
            return ReplicationOutcome.NOT_APPLICABLE;
        }
        try {
            final var documents = readDocuments(dbName, collName, ids);
            return replicator
                    .broadcast(new ReplicationPayload(dbName, collName, ReplicationOp.UPSERT, documents, null));
        } catch (Exception e) {
            // The local commit stands; a failure to ship it is reported to the client and reconciled later.
            logger.warning("Failed to replicate upsert to " + dbName + "|" + collName + ": " + e.getMessage());
            return ReplicationOutcome.TIMEOUT;
        }
    }

    public ReplicationOutcome replicateDelete(String dbName, String collName, List<String> ids) {
        if (notApplicable(dbName, collName)) {
            return ReplicationOutcome.NOT_APPLICABLE;
        }
        return replicator.broadcast(new ReplicationPayload(dbName, collName, ReplicationOp.DELETE, null, ids));
    }

    private boolean doesntCoordinate(String dbName) {
        return !clusterConfig.isEnabled() || Globals.ADMIN_DB_NAME.equals(dbName);
    }

    private boolean notApplicable(String dbName, String collName) {
        return doesntCoordinate(dbName) || !ownershipManager.isOwner(dbName, collName);
    }

    private List<JsonObject> readDocuments(String dbName, String collName, List<String> ids) throws Exception {
        final var documents = new ArrayList<JsonObject>();
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        for (final var id : ids) {
            final var position = Collections.binarySearch(primaryKeyIndex, id);
            if (position >= 0) {
                documents.add(cache.getById(dbName, collName, primaryKeyIndex.get(position)).getData());
            }
        }
        return documents;
    }
}
