package org.techhouse.cluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.admin.CollectionIncarnation;

public class AntiEntropyService implements MembershipListener {
    static final int PULL_BATCH_SIZE = 500;
    private final Logger logger = Logger.logFor(AntiEntropyService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final Set<String> diverged = ConcurrentHashMap.newKeySet();
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
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    reconcile(dbName, collName);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    logger.warning(
                            "Anti-entropy reconciliation of " + dbName + "|" + collName + " failed: " + e.getMessage());
                }
            }
        }
    }

    public AntiEntropyPayload buildDigest(String dbName, String collName) throws Exception {
        return buildDigest(dbName, collName, null, 0L);
    }

    private AntiEntropyPayload staleIncarnationAnswer(String dbName, String collName, long local, long peer) {
        logger.warning("Refusing to describe " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                + " to a peer: its documents belong to incarnation " + local + " and the peer asked for incarnation "
                + peer);
        final var payload = new AntiEntropyPayload(dbName, collName);
        payload.setIncarnationValue(local);
        payload.setStaleIncarnation(true);
        return payload;
    }

    public AntiEntropyPayload buildDigest(String dbName, String collName, String peerSummary, long peerIncarnation)
            throws Exception {
        final var query = new AntiEntropyPayload(dbName, collName);
        query.setSummary(peerSummary);
        query.setIncarnationValue(peerIncarnation);
        return buildDigest(query);
    }

    public AntiEntropyPayload buildDigest(AntiEntropyPayload query) throws Exception {
        final var dbName = query.getDbName();
        final var collName = query.getCollName();
        final var incarnation = CollectionIncarnation.current(dbName, collName);
        if (!CollectionIncarnation.sameLife(incarnation, query.incarnationValue())) {
            return staleIncarnationAnswer(dbName, collName, incarnation, query.incarnationValue());
        }
        final var payload = new AntiEntropyPayload(dbName, collName);
        payload.setIncarnationValue(incarnation);
        payload.setNodeId(selfNodeId());
        final var requested = query.getBucketCount();
        final var bucketCount = requested == 0 ? DigestHash.MIN_BUCKETS : requested;
        lockReadOrSkip(dbName, collName);
        try {
            final var live = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var tombstones = fs.tombstones().view(dbName, collName);
            final var digest = LocalDigest.of(live, tombstones, bucketCount);
            payload.setSummary(digest.summary());
            if (query.getBuckets() != null) {
                payload.setBucketCount(bucketCount);
                payload.setDigest(
                        LocalDigest.entries(live, tombstones, bucketCount, new HashSet<>(query.getBuckets())));
            } else if (digest.summary().equals(query.getSummary())) {
                payload.setSummaryMatch(true);
            } else if (requested == 0 || digest.total() <= 2L * bucketCount) {
                payload.setDigest(LocalDigest.entries(live, tombstones, bucketCount, null));
            } else {
                payload.setBucketCount(bucketCount);
                payload.setBucketSummaries(digest.bucketSummaries());
            }
        } finally {
            locks.releaseRead(dbName, collName);
        }
        return payload;
    }

    static String summaryOf(List<DigestEntry> entries) {
        return LocalDigest.ofEntries(entries, DigestHash.MIN_BUCKETS).summary();
    }

    public AntiEntropyPayload buildPull(String dbName, String collName, List<String> ids) throws Exception {
        return buildPull(dbName, collName, ids, 0L);
    }

    public AntiEntropyPayload buildPull(String dbName, String collName, List<String> ids, long peerIncarnation)
            throws Exception {
        final var incarnation = CollectionIncarnation.current(dbName, collName);
        if (!CollectionIncarnation.sameLife(incarnation, peerIncarnation)) {
            return staleIncarnationAnswer(dbName, collName, incarnation, peerIncarnation);
        }
        final var payload = new AntiEntropyPayload(dbName, collName);
        payload.setIncarnationValue(incarnation);
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
        final LocalDigest local;
        final int bucketCount;
        final long selfIncarnation;
        lockReadOrSkip(dbName, collName);
        try {
            final var live = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var tombstones = fs.tombstones().view(dbName, collName);
            bucketCount = DigestHash.bucketCountFor((long) live.size() + tombstones.size());
            local = LocalDigest.of(live, tombstones, bucketCount);
            selfIncarnation = CollectionIncarnation.current(dbName, collName);
        } finally {
            locks.releaseRead(dbName, collName);
        }
        faultInjector.at(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, Cache.getCollectionIdentifier(dbName, collName));

        final var self = membershipService.getSelf();
        final var peers = membershipService.membershipView().peers(self);
        var everyPeerAnswered = true;
        final var answers = new ArrayList<PeerAnswer>();
        final var differing = new TreeSet<Integer>();
        for (final var member : peers) {
            final var answer = member.isAdminSyncing()
                    ? null
                    : exchange(member.address(), dbName, collName, local, bucketCount, selfIncarnation);
            if (answer == null) {
                everyPeerAnswered = false;
            } else if (!answer.buckets().isEmpty()) {
                answers.add(answer);
                differing.addAll(answer.buckets());
            }
        }
        noteConvergence(Cache.getCollectionIdentifier(dbName, collName), differing.isEmpty() && everyPeerAnswered);
        if (!differing.isEmpty() && !Thread.currentThread().isInterrupted()) {
            repair(dbName, collName, bucketCount, differing, answers, selfIncarnation);
        }
        if (!Thread.currentThread().isInterrupted()) {
            garbageCollectTombstones(dbName, collName, everyPeerAnswered && !peers.isEmpty());
        }
    }

    private void noteConvergence(String collection, boolean converged) {
        if (!converged) {
            diverged.add(collection);
        } else if (diverged.remove(collection)) {
            logger.info("Anti-entropy converged on " + collection + ": every peer's summary matches");
        }
    }

    private PeerAnswer exchange(NodeAddress address, String dbName, String collName, LocalDigest local, int bucketCount,
            long incarnation) {
        final var response = requestDigest(address, dbName, collName, local.summary(), bucketCount, null, incarnation);
        if (staleOrMissing(response, incarnation)) {
            return null;
        }
        if (response.isSummaryMatch()) {
            return new PeerAnswer(address, response.getNodeId(), List.of(), Set.of());
        }
        if (response.getDigest() != null) {
            final var buckets = local.differingBuckets(LocalDigest.ofEntries(response.getDigest(), bucketCount));
            return new PeerAnswer(address, response.getNodeId(), response.getDigest(), buckets);
        }
        final var summaries = response.getBucketSummaries();
        if (summaries == null) {
            return new PeerAnswer(address, response.getNodeId(), List.of(), Set.of());
        }
        if (response.getBucketCount() != bucketCount || summaries.size() != bucketCount) {
            return null;
        }
        final var buckets = local.differingBuckets(summaries);
        if (buckets.isEmpty()) {
            return new PeerAnswer(address, response.getNodeId(), List.of(), buckets);
        }
        final var entries = requestDigest(address, dbName, collName, null, bucketCount, new ArrayList<>(buckets),
                incarnation);
        if (staleOrMissing(entries, incarnation) || entries.getDigest() == null) {
            return null;
        }
        return new PeerAnswer(address, entries.getNodeId(), entries.getDigest(), buckets);
    }

    private static boolean staleOrMissing(AntiEntropyPayload response, long incarnation) {
        return response == null || response.isStaleIncarnation()
                || !CollectionIncarnation.sameLife(incarnation, response.incarnationValue());
    }

    private void repair(String dbName, String collName, int bucketCount, Set<Integer> differing,
            List<PeerAnswer> answers, long selfIncarnation) throws Exception {
        final var localLive = new HashMap<String, LocalEntry>();
        final var localTombstones = new HashMap<String, Long>();
        lockReadOrSkip(dbName, collName);
        try {
            if (CollectionIncarnation.current(dbName, collName) != selfIncarnation) {
                return;
            }
            final var live = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            for (final var entry : LocalDigest.entries(live, fs.tombstones().view(dbName, collName), bucketCount,
                    differing)) {
                if (entry.isDeleted()) {
                    localTombstones.put(entry.getId(), entry.versionValue());
                } else {
                    localLive.put(entry.getId(), new LocalEntry(entry.versionValue(), entry.getLength()));
                }
            }
        } finally {
            locks.releaseRead(dbName, collName);
        }
        final var selfNodeId = selfNodeId();
        final var best = new HashMap<String, Best>();
        localLive.forEach((id, live) -> merge(best, id, live.version(), false, null, selfNodeId, live.length()));
        localTombstones.forEach((id, version) -> merge(best, id, version, true, null, selfNodeId, 0L));
        for (final var answer : answers) {
            for (final var digestEntry : answer.entries()) {
                if (LocalDigest.inBuckets(digestEntry.getId(), bucketCount, answer.buckets())) {
                    merge(best, digestEntry.getId(), digestEntry.versionValue(), digestEntry.isDeleted(),
                            answer.address(), answer.nodeId(), digestEntry.getLength());
                }
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
            } else if (winner.source() != null) {
                final var local = localLive.get(id);
                if (local == null || local.version() < winner.version || pullsOnTieBreak(local, winner, selfNodeId)) {
                    pullByPeer.computeIfAbsent(winner.source, ignored -> new ArrayList<>()).add(id);
                }
            }
        }
        applyDeletes(dbName, collName, deleteIds, deleteVersions, selfIncarnation);
        for (final var pull : pullByPeer.entrySet()) {
            final var ids = pull.getValue();
            for (var from = 0; from < ids.size() && !Thread.currentThread().isInterrupted(); from += PULL_BATCH_SIZE) {
                final var batch = new ArrayList<>(ids.subList(from, Math.min(ids.size(), from + PULL_BATCH_SIZE)));
                pullAndApply(pull.getKey(), dbName, collName, batch, selfIncarnation);
            }
        }
    }

    private void applyDeletes(String dbName, String collName, List<String> deleteIds, List<String> deleteVersions,
            long selfIncarnation) {
        for (var from = 0; from < deleteIds.size()
                && !Thread.currentThread().isInterrupted(); from += PULL_BATCH_SIZE) {
            final var to = Math.min(deleteIds.size(), from + PULL_BATCH_SIZE);
            final var deletes = new ReplicationPayload(dbName, collName, ReplicationOp.DELETE, null,
                    new ArrayList<>(deleteIds.subList(from, to)), new ArrayList<>(deleteVersions.subList(from, to)));
            deletes.setIncarnationValue(selfIncarnation);
            ReplicatedApplyHelper.apply(deletes, clusterConfig.replicationAckTimeoutMs());
        }
    }

    private void pullAndApply(NodeAddress peer, String dbName, String collName, List<String> ids, long incarnation) {
        final var response = requestPull(peer, dbName, collName, ids, incarnation);
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        if (response != null && response.getDocuments() != null && !response.getDocuments().isEmpty()) {
            final var upserts = new ReplicationPayload(dbName, collName, ReplicationOp.UPSERT, response.getDocuments(),
                    null, response.getVersions());
            upserts.setIncarnationValue(incarnation);
            ReplicatedApplyHelper.apply(upserts, clusterConfig.replicationAckTimeoutMs());
        }
    }

    private void garbageCollectTombstones(String dbName, String collName, boolean everyPeerAnswered)
            throws IOException {
        final var retention = clusterConfig.tombstoneRetentionMs();
        if (retention <= 0 || !everyPeerAnswered) {
            return;
        }
        fs.tombstones().compactIfDue(dbName, collName, HybridClock.pack(System.currentTimeMillis() - retention, 0));
    }

    private void merge(Map<String, Best> best, String id, long version, boolean deleted, NodeAddress source,
            String nodeId, long length) {
        final var current = best.get(id);
        if (current == null || wins(version, deleted, nodeId, current)) {
            best.put(id, new Best(version, deleted, source, nodeId, length));
        }
    }

    private String selfNodeId() {
        final var self = membershipService.getSelf();
        return self == null ? null : self.getNodeId();
    }

    private static boolean pullsOnTieBreak(LocalEntry local, Best winner, String selfNodeId) {
        return local.version() == winner.version() && outranksOnNodeId(winner.nodeId(), selfNodeId)
                && (winner.length() == 0 || winner.length() != local.length());
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

    private AntiEntropyPayload requestDigest(NodeAddress address, String dbName, String collName, String summary,
            int bucketCount, List<Integer> buckets, long incarnation) {
        final var message = message(ClusterMessageType.DIGEST);
        final var query = new AntiEntropyPayload(dbName, collName);
        query.setSummary(summary);
        query.setBucketCount(bucketCount);
        query.setBuckets(buckets);
        query.setIncarnationValue(incarnation);
        message.setAntiEntropy(query);
        final var response = send(address, message, ClusterMessageType.DIGEST_ACK);
        return response == null ? null : response.getAntiEntropy();
    }

    private AntiEntropyPayload requestPull(NodeAddress address, String dbName, String collName, List<String> ids,
            long incarnation) {
        final var message = message(ClusterMessageType.PULL);
        final var payload = new AntiEntropyPayload(dbName, collName);
        payload.setIds(ids);
        payload.setIncarnationValue(incarnation);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.warning("Anti-entropy request to " + address + " failed: " + e.getMessage());
            return null;
        }
    }

    private ClusterMessage message(ClusterMessageType type) {
        return new ClusterMessage(null, type, clusterConfig.secret(), membershipService.getSelf(), null);
    }

    private record Best(long version, boolean deleted, NodeAddress source, String nodeId, long length) {
    }

    private record LocalEntry(long version, long length) {
    }

    private record PeerAnswer(NodeAddress address, String nodeId, List<DigestEntry> entries, Set<Integer> buckets) {
    }
}
