package org.techhouse.cluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.OperationRequest;

public class ClusterCoordinator {
    private final Logger logger = Logger.logFor(ClusterCoordinator.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final Replicator replicator = IocContainer.get(Replicator.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final HybridClock hybridClock = IocContainer.get(HybridClock.class);
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
        final var applicability = documentReplicationOutcome(dbName, collName);
        if (applicability != null) {
            return applicability;
        }
        try {
            final var documents = new ArrayList<JsonObject>();
            final var versions = new ArrayList<String>();
            readDocuments(dbName, collName, ids, documents, versions);
            return replicator.broadcast(
                    new ReplicationPayload(dbName, collName, ReplicationOp.UPSERT, documents, null, versions));
        } catch (Exception e) {
            // The local commit stands; a failure to ship it is reported to the client and reconciled later.
            logger.warning("Failed to replicate upsert to " + dbName + "|" + collName + ": " + e.getMessage());
            return ReplicationOutcome.TIMEOUT;
        }
    }

    public Long reserveDelete(String dbName, String collName, List<String> ids) throws java.io.IOException {
        if (documentReplicationOutcome(dbName, collName) != null) {
            return null;
        }
        final var version = hybridClock.next();
        final var present = cache.getPkIndexAndLoadIfNecessary(dbName, collName).stream().map(PkIndexEntry::getValue)
                .collect(Collectors.toSet());
        for (final var id : ids) {
            if (present.contains(id)) {
                fs.appendTombstone(dbName, collName, id, version);
            }
        }
        return version;
    }

    public ReplicationOutcome replicateDelete(String dbName, String collName, List<String> ids, Long reservedVersion) {
        final var applicability = documentReplicationOutcome(dbName, collName);
        if (applicability != null) {
            return applicability;
        }
        final var version = reservedVersion != null ? reservedVersion : hybridClock.next();
        final var versions = new ArrayList<>(Collections.nCopies(ids.size(), Long.toString(version)));
        return replicator
                .broadcast(new ReplicationPayload(dbName, collName, ReplicationOp.DELETE, null, ids, versions));
    }

    // A clustered transaction must not commit without a write quorum (split-brain protection). Always true
    // when clustering is off, so the standalone commit path is unchanged.
    public boolean hasNotTransactionQuorum() {
        return clusterConfig.isEnabled() && !ownershipManager.hasQuorum();
    }

    public Map<String, Long> reserveTransactionTombstones(Transaction transaction) throws IOException {
        if (!clusterConfig.isEnabled()) {
            return Map.of();
        }
        final var reserved = new HashMap<String, Long>();
        for (final var collId : transaction.touchedCollections()) {
            final var overlay = transaction.overlayFor(collId);
            if (overlay == null) {
                continue;
            }
            final var deleteIds = new ArrayList<String>();
            for (final var overlayEntry : overlay.entrySet()) {
                if (Transaction.isTombstone(overlayEntry.getValue())) {
                    deleteIds.add(overlayEntry.getKey());
                }
            }
            if (deleteIds.isEmpty()) {
                continue;
            }
            final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            final var version = hybridClock.next();
            for (final var id : deleteIds) {
                fs.appendTombstone(parts[0], parts[1], id, version);
            }
            reserved.put(collId, version);
        }
        return reserved;
    }

    public ReplicationOutcome replicateTransaction(Transaction transaction) {
        return replicateTransaction(transaction, Map.of());
    }

    public ReplicationOutcome replicateTransaction(Transaction transaction, Map<String, Long> reservedVersions) {
        if (!clusterConfig.isEnabled()) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        final var entries = new ArrayList<ReplicationPayload>();
        try {
            for (final var collId : transaction.touchedCollections()) {
                final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
                // Only replicate collections this node owns — a 2PC participant owns its slice's collections
                // and replicates them to their replicas; a collection owned elsewhere is that owner's to ship.
                if (ownershipManager.isOwner(parts[0], parts[1])) {
                    buildCollectionEntries(parts[0], parts[1], transaction.overlayFor(collId), entries,
                            reservedVersions.get(collId));
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to build transaction replication batch: " + e.getMessage());
            return ReplicationOutcome.TIMEOUT;
        }
        if (entries.isEmpty()) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        return replicator.broadcastTx(new TxReplicationPayload(entries));
    }

    private void buildCollectionEntries(String dbName, String collName, Map<String, JsonObject> overlay,
            List<ReplicationPayload> entries, Long reservedVersion) throws Exception {
        final var upsertIds = new ArrayList<String>();
        final var deleteIds = new ArrayList<String>();
        for (final var overlayEntry : overlay.entrySet()) {
            if (Transaction.isTombstone(overlayEntry.getValue())) {
                deleteIds.add(overlayEntry.getKey());
            } else {
                upsertIds.add(overlayEntry.getKey());
            }
        }
        if (!upsertIds.isEmpty()) {
            final var documents = new ArrayList<JsonObject>();
            final var versions = new ArrayList<String>();
            readDocuments(dbName, collName, upsertIds, documents, versions);
            entries.add(new ReplicationPayload(dbName, collName, ReplicationOp.UPSERT, documents, null, versions));
        }
        if (!deleteIds.isEmpty()) {
            final long version;
            if (reservedVersion != null) {
                version = reservedVersion;
            } else {
                version = hybridClock.next();
                for (final var id : deleteIds) {
                    fs.appendTombstone(dbName, collName, id, version);
                }
            }
            final var versions = new ArrayList<>(Collections.nCopies(deleteIds.size(), Long.toString(version)));
            entries.add(new ReplicationPayload(dbName, collName, ReplicationOp.DELETE, null, deleteIds, versions));
        }
    }

    // A node without a write quorum must not apply admin/DDL ops (split-brain protection).
    public WriteGuard guardAdmin() {
        if (!clusterConfig.isEnabled()) {
            return WriteGuard.allow();
        }
        return ownershipManager.hasQuorum() ? WriteGuard.allow() : WriteGuard.noQuorum();
    }

    // Only the coordinator replicates, so peers applying an inbound REPLICATE_ADMIN never re-broadcast.
    public ReplicationOutcome replicateAdminOp(OperationRequest request, String actingUser) {
        if (!clusterConfig.isEnabled()) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        if (!ownershipManager.isAdminCoordinator()) {
            return ReplicationOutcome.NOT_COORDINATOR;
        }
        return replicator.broadcastAdmin(eJson.toJson(request), actingUser);
    }

    // Ships the committed admin/users record so the salted password hash is identical on every node rather
    // than re-hashed per node.
    public ReplicationOutcome replicateUserOp(String username, boolean delete) {
        if (!clusterConfig.isEnabled()) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        if (!ownershipManager.isAdminCoordinator()) {
            return ReplicationOutcome.NOT_COORDINATOR;
        }
        final ReplicationPayload payload;
        if (delete) {
            payload = new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                    ReplicationOp.DELETE, null, List.of(username));
        } else {
            final var entry = cache.getAdminUserEntry(username);
            if (entry == null) {
                return ReplicationOutcome.NOT_COORDINATOR;
            }
            payload = new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                    ReplicationOp.UPSERT, List.of(entry.getData()), null);
        }
        return replicator.broadcastUser(payload);
    }

    private boolean doesntCoordinate(String dbName) {
        return !clusterConfig.isEnabled() || Globals.ADMIN_DB_NAME.equals(dbName);
    }

    private ReplicationOutcome documentReplicationOutcome(String dbName, String collName) {
        if (doesntCoordinate(dbName)) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        if (!ownershipManager.isOwner(dbName, collName)) {
            return ReplicationOutcome.NOT_OWNER;
        }
        return null;
    }

    public boolean stillOwns(String dbName, String collName) {
        return doesntCoordinate(dbName) || ownershipManager.isOwner(dbName, collName);
    }

    private void readDocuments(String dbName, String collName, List<String> ids, List<JsonObject> documents,
            List<String> versions) throws Exception {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        for (final var id : ids) {
            final var position = Collections.binarySearch(primaryKeyIndex, id);
            if (position >= 0) {
                final var indexEntry = primaryKeyIndex.get(position);
                documents.add(cache.getById(dbName, collName, indexEntry).getData());
                versions.add(Long.toString(indexEntry.getVersion()));
            }
        }
    }
}
