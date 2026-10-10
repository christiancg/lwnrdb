package org.techhouse.cluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.admin.AdminRecord;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
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
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.tx.ApplyOutcome;
import org.techhouse.ops.tx.VersionedApply;

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
                    stamped(new ReplicationPayload(dbName, collName, ReplicationOp.UPSERT, documents, null, versions)));
        } catch (Exception e) {
            // The local commit stands; a failure to ship it is reported to the client and reconciled later.
            logger.warning("Failed to replicate upsert to " + dbName + "|" + collName + ": " + e.getMessage());
            return ReplicationOutcome.TIMEOUT;
        }
    }

    public DeleteReservation reserveDelete(String dbName, String collName, List<String> ids)
            throws java.io.IOException {
        final var applicability = documentReplicationOutcome(dbName, collName);
        if (applicability == ReplicationOutcome.NOT_OWNER) {
            return DeleteReservation.lostOwnership();
        }
        if (applicability != null) {
            return DeleteReservation.notClustered();
        }
        final var version = hybridClock.next();
        final var present = cache.getPkIndexAndLoadIfNecessary(dbName, collName).stream().map(PkIndexEntry::getValue)
                .collect(Collectors.toSet());
        for (final var id : ids) {
            if (present.contains(id)) {
                fs.tombstones().append(dbName, collName, id, version);
            }
        }
        return DeleteReservation.reserved(version);
    }

    public void retractDelete(String dbName, String collName, List<String> ids, Long reservedVersion)
            throws java.io.IOException {
        if (reservedVersion == null) {
            return;
        }
        for (final var id : ids) {
            fs.tombstones().retract(dbName, collName, id, reservedVersion);
        }
    }

    public ReplicationOutcome replicateDelete(String dbName, String collName, List<String> ids, Long reservedVersion) {
        final var applicability = documentReplicationOutcome(dbName, collName);
        if (applicability != null) {
            return applicability;
        }
        final var version = reservedVersion != null ? reservedVersion : hybridClock.next();
        final var versions = new ArrayList<>(Collections.nCopies(ids.size(), Long.toString(version)));
        return replicator.broadcast(
                stamped(new ReplicationPayload(dbName, collName, ReplicationOp.DELETE, null, ids, versions)));
    }

    // A clustered transaction must not commit without a write quorum (split-brain protection). Always true
    // when clustering is off, so the standalone commit path is unchanged.
    public boolean hasNotTransactionQuorum() {
        return clusterConfig.isEnabled() && !ownershipManager.hasQuorum();
    }

    public void reserveTransactionTombstones(Transaction transaction) throws IOException {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        for (final var collId : List.copyOf(transaction.touchedCollections())) {
            final var overlay = transaction.overlayFor(collId);
            if (overlay == null) {
                continue;
            }
            final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            for (final var id : deletedIds(overlay)) {
                reserveTombstone(transaction, collId, parts[0], parts[1], id);
            }
        }
    }

    private void reserveTombstone(Transaction transaction, String collId, String dbName, String collName, String id)
            throws IOException {
        final var version = transaction.versionOf(collId, id);
        final var stored = VersionedApply.storedEntry(dbName, collName, id);
        final var outcome = ApplyOutcome.forDelete(stored == null ? 0L : stored.getVersion(), stored != null, version);
        if (outcome == ApplyOutcome.SUPERSEDED) {
            transaction.forget(collId, id);
            return;
        }
        final var tombstoneVersion = version > 0 ? version : hybridClock.next();
        transaction.recordDelete(collId, id, tombstoneVersion);
        fs.tombstones().append(dbName, collName, id, tombstoneVersion);
    }

    private static List<String> deletedIds(Map<String, JsonObject> overlay) {
        final var deleteIds = new ArrayList<String>();
        for (final var overlayEntry : overlay.entrySet()) {
            if (Transaction.isTombstone(overlayEntry.getValue())) {
                deleteIds.add(overlayEntry.getKey());
            }
        }
        return deleteIds;
    }

    public ReplicationOutcome replicateTransaction(Transaction transaction) {
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
                    buildCollectionEntries(parts[0], parts[1], transaction, collId, entries);
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

    private static ReplicationPayload stamped(ReplicationPayload payload) {
        payload.setIncarnationValue(CollectionIncarnation.current(payload.getDbName(), payload.getCollName()));
        return payload;
    }

    private void buildCollectionEntries(String dbName, String collName, Transaction transaction, String collId,
            List<ReplicationPayload> entries) throws Exception {
        final var overlay = transaction.overlayFor(collId);
        final var upsertIds = new ArrayList<String>();
        final var deleteIds = deletedIds(overlay);
        for (final var overlayEntry : overlay.entrySet()) {
            if (!Transaction.isTombstone(overlayEntry.getValue())) {
                upsertIds.add(overlayEntry.getKey());
            }
        }
        if (!upsertIds.isEmpty()) {
            final var documents = new ArrayList<JsonObject>();
            final var versions = new ArrayList<String>();
            readDocuments(dbName, collName, upsertIds, documents, versions);
            entries.add(
                    stamped(new ReplicationPayload(dbName, collName, ReplicationOp.UPSERT, documents, null, versions)));
        }
        if (!deleteIds.isEmpty()) {
            final var versions = new ArrayList<String>();
            for (final var id : deleteIds) {
                versions.add(Long.toString(transaction.versionOf(collId, id)));
            }
            entries.add(
                    stamped(new ReplicationPayload(dbName, collName, ReplicationOp.DELETE, null, deleteIds, versions)));
        }
    }

    // A node without a write quorum must not apply admin/DDL ops (split-brain protection).
    public WriteGuard guardAdmin() {
        if (!clusterConfig.isEnabled()) {
            return WriteGuard.allow();
        }
        return ownershipManager.hasQuorum() ? WriteGuard.allow() : WriteGuard.noQuorum();
    }

    public ReplicationOutcome replicateAdminRecords(List<AdminRecord> records) {
        return replicateAdmin(() -> replicator.broadcastAdmin(new AdminSnapshotPayload(records)));
    }

    public ReplicationOutcome broadcastReindex(ReindexRequest request) {
        return replicateAdmin(() -> replicator.broadcastReindex(eJson.toJson(request)));
    }

    private ReplicationOutcome replicateAdmin(Supplier<ReplicationOutcome> broadcast) {
        if (!clusterConfig.isEnabled()) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        if (!ownershipManager.isAdminCoordinator()) {
            return ReplicationOutcome.NOT_COORDINATOR;
        }
        return broadcast.get();
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
