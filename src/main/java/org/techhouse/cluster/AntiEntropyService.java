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
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.utils.JsonUtils;

public class AntiEntropyService implements MembershipListener {
    private final Logger logger = Logger.logFor(AntiEntropyService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final CoalescingSweep sweep = new CoalescingSweep(logger, "cluster-anti-entropy", "Anti-entropy",
            this::reconcileAllCollections);

    public void start() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        sweep.startPeriodic(clusterConfig.antiEntropyIntervalMs());
    }

    public void stop() {
        stop(clusterConfig.antiEntropyIntervalMs());
    }

    public void stop(long awaitMillis) {
        sweep.stop(awaitMillis);
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

    private void lockReadOrSkip(String dbName, String collName) throws InterruptedException {
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockRead(dbName, collName, waitMillis)) {
            throw new CollectionBusyException(dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName, waitMillis);
        }
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
        return buildDigest(dbName, collName, null);
    }

    public AntiEntropyPayload buildDigest(String dbName, String collName, String peerSummary) throws Exception {
        final var payload = new AntiEntropyPayload(dbName, collName);
        final var entries = new ArrayList<DigestEntry>();
        final var selfNodeId = selfNodeId();
        lockReadOrSkip(dbName, collName);
        try {
            for (final var entry : cache.getPkIndexAndLoadIfNecessary(dbName, collName)) {
                entries.add(
                        new DigestEntry(entry.getValue(), entry.getVersion(), false, selfNodeId, entry.getLength()));
            }
            for (final var tombstone : fs.readTombstones(dbName, collName).entrySet()) {
                entries.add(new DigestEntry(tombstone.getKey(), tombstone.getValue(), true, selfNodeId));
            }
        } finally {
            locks.releaseRead(dbName, collName);
        }
        final var summary = summaryOf(entries);
        payload.setSummary(summary);
        if (peerSummary != null && peerSummary.equals(summary)) {
            payload.setSummaryMatch(true);
            return payload;
        }
        payload.setDigest(entries);
        return payload;
    }

    static String summaryOf(List<DigestEntry> entries) {
        final var canonical = new ArrayList<String>(entries.size());
        for (final var entry : entries) {
            canonical.add(entry.getId() + '|' + entry.getVersion() + '|' + entry.isDeleted() + '|' + entry.getLength());
        }
        canonical.sort(null);
        return entries.size() + ":" + JsonUtils.sha256(String.join("\u001f", canonical));
    }

    public AntiEntropyPayload buildPull(String dbName, String collName, List<String> ids) throws Exception {
        final var payload = new AntiEntropyPayload(dbName, collName);
        final var documents = new ArrayList<JsonObject>();
        final var versions = new ArrayList<String>();
        lockReadOrSkip(dbName, collName);
        try {
            final var pkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            for (final var id : ids) {
                final var position = Collections.binarySearch(pkIndex, id);
                if (position >= 0) {
                    final var indexEntry = pkIndex.get(position).detachedCopy();
                    documents.add(cache.getById(dbName, collName, indexEntry).getData());
                    versions.add(Long.toString(indexEntry.getVersion()));
                }
            }
        } finally {
            locks.releaseRead(dbName, collName);
        }
        payload.setDocuments(documents);
        payload.setVersions(versions);
        return payload;
    }

    void reconcile(String dbName, String collName) throws Exception {
        final var localLive = new HashMap<String, Long>();
        final Map<String, Long> localTombstones;
        lockReadOrSkip(dbName, collName);
        try {
            for (final var entry : cache.getPkIndexAndLoadIfNecessary(dbName, collName)) {
                localLive.put(entry.getValue(), entry.getVersion());
            }
            localTombstones = fs.readTombstones(dbName, collName);
        } finally {
            locks.releaseRead(dbName, collName);
        }

        final var selfNodeId = selfNodeId();
        final var best = new HashMap<String, Best>();
        localLive.forEach((id, version) -> merge(best, id, version, false, null, selfNodeId));
        localTombstones.forEach((id, version) -> merge(best, id, version, true, null, selfNodeId));

        final var localEntries = new ArrayList<DigestEntry>(localLive.size() + localTombstones.size());
        localLive.forEach((id, version) -> localEntries.add(new DigestEntry(id, version, false)));
        localTombstones.forEach((id, version) -> localEntries.add(new DigestEntry(id, version, true)));
        final var localSummary = summaryOf(localEntries);

        final var self = membershipService.getSelf();
        final var peers = membershipService.membershipView().peers(self);
        var everyPeerAnswered = true;
        for (final var member : peers) {
            final var response = requestDigest(member.address(), dbName, collName, localSummary);
            if (response == null) {
                everyPeerAnswered = false;
                continue;
            }
            if (response.isSummaryMatch() || response.getDigest() == null) {
                continue;
            }
            for (final var digestEntry : response.getDigest()) {
                merge(best, digestEntry.getId(), digestEntry.versionValue(), digestEntry.isDeleted(), member.address(),
                        digestEntry.getNodeId());
            }
        }

        final var pullByPeer = new HashMap<NodeAddress, List<String>>();
        final var deleteIds = new ArrayList<String>();
        final var deleteVersions = new ArrayList<String>();
        for (final var idBest : best.entrySet()) {
            final var id = idBest.getKey();
            final var winner = idBest.getValue();
            if (winner.deleted) {
                final var localTombstone = localTombstones.get(id);
                if (localLive.containsKey(id) || localTombstone == null || localTombstone < winner.version) {
                    deleteIds.add(id);
                    deleteVersions.add(Long.toString(winner.version));
                }
            } else if (winner.source != null) {
                final var localVersion = localLive.get(id);
                if (localVersion == null || localVersion < winner.version
                        || (localVersion == winner.version && outranksOnNodeId(winner.nodeId, selfNodeId))) {
                    pullByPeer.computeIfAbsent(winner.source, ignored -> new ArrayList<>()).add(id);
                }
            }
        }

        if (!deleteIds.isEmpty()) {
            ReplicatedApplyHelper.apply(
                    new ReplicationPayload(dbName, collName, ReplicationOp.DELETE, null, deleteIds, deleteVersions),
                    clusterConfig.replicationAckTimeoutMs());
        }
        for (final var pull : pullByPeer.entrySet()) {
            final var response = requestPull(pull.getKey(), dbName, collName, pull.getValue());
            if (response != null && response.getDocuments() != null && !response.getDocuments().isEmpty()) {
                ReplicatedApplyHelper.apply(new ReplicationPayload(dbName, collName, ReplicationOp.UPSERT,
                        response.getDocuments(), null, response.getVersions()),
                        clusterConfig.replicationAckTimeoutMs());
            }
        }
        garbageCollectTombstones(dbName, collName, everyPeerAnswered && !peers.isEmpty());
    }

    private void garbageCollectTombstones(String dbName, String collName, boolean everyPeerAnswered)
            throws IOException {
        final var retention = clusterConfig.tombstoneRetentionMs();
        if (retention <= 0 || !everyPeerAnswered) {
            return;
        }
        fs.compactTombstones(dbName, collName, HybridClock.pack(System.currentTimeMillis() - retention, 0));
    }

    private void merge(Map<String, Best> best, String id, long version, boolean deleted, NodeAddress source,
            String nodeId) {
        final var current = best.get(id);
        if (current == null || wins(version, deleted, nodeId, current)) {
            best.put(id, new Best(version, deleted, source, nodeId));
        }
    }

    private String selfNodeId() {
        final var self = membershipService.getSelf();
        return self == null ? null : self.getNodeId();
    }

    private static boolean outranksOnNodeId(String winnerNodeId, String selfNodeId) {
        return winnerNodeId != null && selfNodeId != null && winnerNodeId.compareTo(selfNodeId) > 0;
    }

    private static boolean wins(long version, boolean deleted, String nodeId, Best current) {
        if (version != current.version()) {
            return version > current.version();
        }
        if (deleted != current.deleted()) {
            return deleted;
        }
        final var currentNodeId = current.nodeId();
        if (nodeId == null || currentNodeId == null) {
            return false;
        }
        return nodeId.compareTo(currentNodeId) > 0;
    }

    private AntiEntropyPayload requestDigest(NodeAddress address, String dbName, String collName, String summary) {
        final var message = message(ClusterMessageType.DIGEST);
        final var query = new AntiEntropyPayload(dbName, collName);
        query.setSummary(summary);
        message.setAntiEntropy(query);
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

    private record Best(long version, boolean deleted, NodeAddress source, String nodeId) {
    }
}
