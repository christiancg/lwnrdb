package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.OperationRequest;

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
    private final EJson eJson = IocContainer.get(EJson.class);

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

    // Admin/DDL ops are serialized by the admin coordinator; a node without a write quorum must not apply
    // them (split-brain protection), mirroring the per-collection write guard.
    public WriteGuard guardAdmin() {
        if (!clusterConfig.isEnabled()) {
            return WriteGuard.allow();
        }
        return ownershipManager.hasQuorum() ? WriteGuard.allow() : WriteGuard.noQuorum();
    }

    // Broadcasts an admin/DDL op (re-executed as actingUser) to a majority. Only the coordinator replicates,
    // so peers applying an inbound REPLICATE_ADMIN never re-broadcast.
    public ReplicationOutcome replicateAdminOp(OperationRequest request, String actingUser) {
        if (!clusterConfig.isEnabled() || !ownershipManager.isAdminCoordinator()) {
            return ReplicationOutcome.NOT_APPLICABLE;
        }
        return replicator.broadcastAdmin(eJson.toJson(request), actingUser);
    }

    // Replicates a user mutation by shipping the committed admin/users record (or a delete by username), so
    // the salted password hash is identical on every node rather than re-hashed per node.
    public ReplicationOutcome replicateUserOp(String username, boolean delete) {
        if (!clusterConfig.isEnabled() || !ownershipManager.isAdminCoordinator()) {
            return ReplicationOutcome.NOT_APPLICABLE;
        }
        final ReplicationPayload payload;
        if (delete) {
            payload = new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                    ReplicationOp.DELETE, null, List.of(username));
        } else {
            final var entry = cache.getAdminUserEntry(username);
            if (entry == null) {
                return ReplicationOutcome.NOT_APPLICABLE;
            }
            payload = new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                    ReplicationOp.UPSERT, List.of(entry.getData()), null);
        }
        return replicator.broadcastUser(payload);
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
