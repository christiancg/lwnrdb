package org.techhouse.cluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ReplicatedApplyHelper;

public class AntiEntropyService implements MembershipListener {
    private final Logger logger = Logger.logFor(AntiEntropyService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final CoalescingSweep sweep = new CoalescingSweep(logger, "cluster-anti-entropy", "Anti-entropy",
            this::reconcileAllCollections);

    public void start() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        sweep.startPeriodic(clusterConfig.antiEntropyIntervalMs());
    }

    public void stop() {
        sweep.stopPeriodic();
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        sweep.schedule();
    }

    public void reconcileNow() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        sweep.schedule();
    }

    private void reconcileAllCollections() {
        for (final var dbName : cache.getUserDatabaseNames()) {
            for (final var collName : cache.getCollectionNamesForDatabase(dbName)) {
                try {
                    reconcile(dbName, collName);
                } catch (Exception e) {
                    logger.warning(
                            "Anti-entropy reconciliation of " + dbName + "|" + collName + " failed: " + e.getMessage());
                }
            }
        }
    }

    public AntiEntropyPayload buildDigest(String dbName, String collName) throws Exception {
        final var payload = new AntiEntropyPayload(dbName, collName);
        final var entries = new ArrayList<DigestEntry>();
        for (final var entry : cache.getPkIndexAndLoadIfNecessary(dbName, collName)) {
            entries.add(new DigestEntry(entry.getValue(), entry.getVersion(), false));
        }
        for (final var tombstone : fs.readTombstones(dbName, collName).entrySet()) {
            entries.add(new DigestEntry(tombstone.getKey(), tombstone.getValue(), true));
        }
        payload.setDigest(entries);
        return payload;
    }

    public AntiEntropyPayload buildPull(String dbName, String collName, List<String> ids) throws Exception {
        final var payload = new AntiEntropyPayload(dbName, collName);
        final var documents = new ArrayList<JsonObject>();
        final var versions = new ArrayList<Long>();
        final var pkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        for (final var id : ids) {
            final var position = Collections.binarySearch(pkIndex, id);
            if (position >= 0) {
                final var indexEntry = pkIndex.get(position);
                documents.add(cache.getById(dbName, collName, indexEntry).getData());
                versions.add(indexEntry.getVersion());
            }
        }
        payload.setDocuments(documents);
        payload.setVersions(versions);
        return payload;
    }

    void reconcile(String dbName, String collName) throws Exception {
        final var localLive = new HashMap<String, Long>();
        for (final var entry : cache.getPkIndexAndLoadIfNecessary(dbName, collName)) {
            localLive.put(entry.getValue(), entry.getVersion());
        }
        final var localTombstones = fs.readTombstones(dbName, collName);

        final var best = new HashMap<String, Best>();
        localLive.forEach((id, version) -> merge(best, id, version, false, null));
        localTombstones.forEach((id, version) -> merge(best, id, version, true, null));

        final var self = membershipService.getSelf();
        for (final var member : membershipService.membershipView().peers(self)) {
            final var response = requestDigest(member.address(), dbName, collName);
            if (response == null) {
                continue;
            }
            for (final var digestEntry : response.getDigest()) {
                merge(best, digestEntry.getId(), digestEntry.getVersion(), digestEntry.isDeleted(), member.address());
            }
        }

        final var pullByPeer = new HashMap<NodeAddress, List<String>>();
        final var deleteIds = new ArrayList<String>();
        final var deleteVersions = new ArrayList<Long>();
        for (final var idBest : best.entrySet()) {
            final var id = idBest.getKey();
            final var winner = idBest.getValue();
            if (winner.deleted) {
                final var localTombstone = localTombstones.get(id);
                if (localLive.containsKey(id) || localTombstone == null || localTombstone < winner.version) {
                    deleteIds.add(id);
                    deleteVersions.add(winner.version);
                }
            } else if (winner.source != null) {
                final var localVersion = localLive.get(id);
                if (localVersion == null || localVersion < winner.version) {
                    pullByPeer.computeIfAbsent(winner.source, ignored -> new ArrayList<>()).add(id);
                }
            }
        }

        if (!deleteIds.isEmpty()) {
            ReplicatedApplyHelper.apply(
                    new ReplicationPayload(dbName, collName, ReplicationOp.DELETE, null, deleteIds, deleteVersions));
        }
        for (final var pull : pullByPeer.entrySet()) {
            final var response = requestPull(pull.getKey(), dbName, collName, pull.getValue());
            if (response != null && response.getDocuments() != null && !response.getDocuments().isEmpty()) {
                ReplicatedApplyHelper.apply(new ReplicationPayload(dbName, collName, ReplicationOp.UPSERT,
                        response.getDocuments(), null, response.getVersions()));
            }
        }
        garbageCollectTombstones(dbName, collName);
    }

    private void garbageCollectTombstones(String dbName, String collName) throws IOException {
        final var retention = clusterConfig.tombstoneRetentionMs();
        if (retention <= 0) {
            return;
        }
        fs.compactTombstones(dbName, collName, System.currentTimeMillis() - retention);
    }

    private void merge(Map<String, Best> best, String id, long version, boolean deleted, NodeAddress source) {
        final var current = best.get(id);
        // Higher version wins; on an equal version a tombstone beats a live document (delete wins ties).
        if (current == null || version > current.version
                || (version == current.version && deleted && !current.deleted)) {
            best.put(id, new Best(version, deleted, source));
        }
    }

    private AntiEntropyPayload requestDigest(NodeAddress address, String dbName, String collName) {
        final var message = message(ClusterMessageType.DIGEST);
        message.setAntiEntropy(new AntiEntropyPayload(dbName, collName));
        final var response = send(address, message, ClusterMessageType.DIGEST_ACK);
        return response == null ? null : response.getAntiEntropy();
    }

    private AntiEntropyPayload requestPull(NodeAddress address, String dbName, String collName, List<String> ids) {
        final var message = message(ClusterMessageType.PULL);
        final var payload = new AntiEntropyPayload(dbName, collName);
        payload.setIds(ids);
        message.setAntiEntropy(payload);
        final var response = send(address, message, ClusterMessageType.PULL_ACK);
        return response == null ? null : response.getAntiEntropy();
    }

    private ClusterMessage send(NodeAddress address, ClusterMessage message, ClusterMessageType expected) {
        try {
            final var response = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == expected) {
                return response;
            }
            logger.warning("Anti-entropy request to " + address + " not acknowledged: " + response.getErrorMessage());
            return null;
        } catch (Exception e) {
            logger.warning("Anti-entropy request to " + address + " failed: " + e.getMessage());
            return null;
        }
    }

    private ClusterMessage message(ClusterMessageType type) {
        return new ClusterMessage(null, type, clusterConfig.secret(), membershipService.getSelf(), null);
    }

    private record Best(long version, boolean deleted, NodeAddress source) {
    }
}
