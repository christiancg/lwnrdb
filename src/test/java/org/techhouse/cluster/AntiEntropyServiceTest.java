package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AntiEntropyServiceTest {
    private final AntiEntropyService service = IocContainer.get(AntiEntropyService.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private MembershipService realMembership;
    private PeerConnectionPool realPool;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    private void seed() {
        final var obj = new JsonObject();
        obj.addProperty("_id", "a");
        obj.addProperty("v", 1);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(obj);
        processor.processMessage(request);
    }

    private long versionOf() throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(e -> e.getValue().equals("a")).findFirst().orElseThrow().getVersion();
    }

    private boolean hasLive() throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .anyMatch(e -> e.getValue().equals("a"));
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        realMembership = TestUtils.getPrivateField(service, "membershipService", MembershipService.class);
        realPool = TestUtils.getPrivateField(service, "pool", PeerConnectionPool.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(service, "membershipService", realMembership);
        TestUtils.setPrivateField(service, "pool", realPool);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void injectPeer(PeerConnectionPool pool) throws Exception {
        final var self = node("self", 5000);
        final var peer = node("peer", 5001);
        final var membership = mock(MembershipService.class);
        when(membership.getSelf()).thenReturn(self);
        when(membership.membershipView()).thenReturn(new MembershipView(List.of(self, peer)));
        TestUtils.setPrivateField(service, "membershipService", membership);
        TestUtils.setPrivateField(service, "pool", pool);
    }

    @Test
    public void test_build_digest_includes_live_docs_and_tombstones() throws Exception {
        seed();
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "gone", 555L);
        final var digest = service.buildDigest(TestGlobals.DB, TestGlobals.COLL).getDigest();
        final var live = digest.stream().filter(e -> e.getId().equals("a")).findFirst().orElseThrow();
        assertFalse(live.isDeleted());
        final var tombstone = digest.stream().filter(e -> e.getId().equals("gone")).findFirst().orElseThrow();
        assertTrue(tombstone.isDeleted());
        assertEquals("555", tombstone.getVersion());
        assertEquals(555L, tombstone.versionValue());
    }

    @Test
    public void test_summary_matches_between_owner_and_replica_after_a_write() throws Exception {
        seed();
        for (var logical = 1; logical <= 8; logical++) {
            fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "gone-" + logical,
                    HybridClock.pack(System.currentTimeMillis(), logical));
        }
        final var owner = service.buildDigest(TestGlobals.DB, TestGlobals.COLL);

        final var asReceived = eJson.fromJson(eJson.toJson(owner), AntiEntropyPayload.class);

        assertEquals(owner.getSummary(), AntiEntropyService.summaryOf(asReceived.getDigest()));
    }

    @Test
    public void test_the_summary_reconcile_sends_matches_the_digest_it_would_build() throws Exception {
        seed();
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "gone", HybridClock.pack(System.currentTimeMillis(), 1));
        final var sentSummary = new AtomicReference<String>();
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            sentSummary.compareAndSet(null, message.getAntiEntropy().getSummary());
            final var response = new ClusterMessage();
            response.setType(ClusterMessageType.DIGEST_ACK);
            final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
            payload.setSummaryMatch(true);
            response.setAntiEntropy(payload);
            return response;
        });
        injectPeer(pool);

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(service.buildDigest(TestGlobals.DB, TestGlobals.COLL).getSummary(), sentSummary.get(),
                "a converged peer answers summaryMatch only when the summary reconcile sends is built exactly"
                        + " as buildDigest builds its own, document length included");
    }

    private Thread backgroundWriter(AtomicBoolean stop) {
        return new Thread(() -> {
            for (var i = 0; i < 400 && !stop.get(); i++) {
                final var object = new JsonObject();
                object.addProperty("_id", "w" + i);
                object.addProperty("v", i);
                final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
                request.setObject(object);
                request.set_id("w" + i);
                processor.processMessage(request);
            }
        }, "digest-writer");
    }

    @Test
    public void test_build_digest_survives_a_concurrent_save() throws Exception {
        seed();
        final var failure = new AtomicReference<Throwable>();
        final var stop = new AtomicBoolean();
        final var writer = backgroundWriter(stop);
        writer.start();
        try {
            for (var i = 0; i < 200; i++) {
                try {
                    service.buildDigest(TestGlobals.DB, TestGlobals.COLL);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    break;
                }
            }
        } finally {
            stop.set(true);
            writer.join(10_000);
        }

        assertNull(failure.get(),
                "a digest built while the collection is being written must not blow up: " + failure.get());
    }

    @Test
    public void test_build_pull_returns_documents_and_versions() throws Exception {
        seed();
        final var pull = service.buildPull(TestGlobals.DB, TestGlobals.COLL, List.of("a", "missing"));
        assertEquals(1, pull.getDocuments().size());
        assertEquals(1, pull.getVersions().size());
    }

    @Test
    public void test_reconcile_pulls_newer_document_from_peer() throws Exception {
        final var doc = new JsonObject();
        doc.addProperty("_id", "a");
        doc.addProperty("v", 9);
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var response = new ClusterMessage();
            final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
            if (message.getType() == ClusterMessageType.DIGEST) {
                response.setType(ClusterMessageType.DIGEST_ACK);
                payload.setDigest(List.of(new DigestEntry("a", 1000L, false)));
            } else {
                response.setType(ClusterMessageType.PULL_ACK);
                payload.setDocuments(List.of(doc));
                payload.setVersions(List.of("1000"));
            }
            response.setAntiEntropy(payload);
            return response;
        });
        injectPeer(pool);

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(hasLive());
        assertEquals(1000L, versionOf());
    }

    @Test
    public void test_reconcile_applies_newer_tombstone_from_peer() throws Exception {
        seed();
        final var tombstoneVersion = HybridClock.pack(System.currentTimeMillis() + 1_000_000L, 0);
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(_ -> {
            final var response = new ClusterMessage();
            response.setType(ClusterMessageType.DIGEST_ACK);
            final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
            payload.setDigest(List.of(new DigestEntry("a", tombstoneVersion, true)));
            response.setAntiEntropy(payload);
            return response;
        });
        injectPeer(pool);

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertFalse(hasLive());
        assertEquals(tombstoneVersion, fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).get("a"));
    }

    @Test
    public void test_on_membership_change_when_enabled_runs_reconcile() throws Exception {
        final var config = Configuration.getInstance();
        final var original = config.isClusterEnabled();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        try {
            seed();
            final var pool = mock(PeerConnectionPool.class);
            when(pool.request(any(), any(), anyLong())).thenAnswer(_ -> {
                final var response = new ClusterMessage();
                response.setType(ClusterMessageType.DIGEST_ACK);
                response.setAntiEntropy(new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL));
                response.getAntiEntropy().setDigest(List.of());
                return response;
            });
            injectPeer(pool);
            service.onMembershipChanged(new MembershipView(List.of(node("self", 5000), node("peer", 5001))));
            verify(pool, timeout(3000).atLeastOnce()).request(any(), any(), anyLong());
        } finally {
            TestUtils.setPrivateField(config, "clusterEnabled", original);
        }
    }

    @Test
    public void test_on_membership_change_when_disabled_is_noop() throws Exception {
        final var config = Configuration.getInstance();
        final var original = config.isClusterEnabled();
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        try {
            final var pool = mock(PeerConnectionPool.class);
            injectPeer(pool);
            service.onMembershipChanged(new MembershipView(List.of(node("self", 5000), node("peer", 5001))));
            verify(pool, after(400).never()).request(any(), any(), anyLong());
        } finally {
            TestUtils.setPrivateField(config, "clusterEnabled", original);
        }
    }

    @Test
    public void test_reconcile_keeps_local_when_newer_than_peer() throws Exception {
        seed();
        final var localVersion = versionOf();
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(_ -> {
            final var response = new ClusterMessage();
            response.setType(ClusterMessageType.DIGEST_ACK);
            final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
            payload.setDigest(List.of(new DigestEntry("a", 1L, false)));
            response.setAntiEntropy(payload);
            return response;
        });
        injectPeer(pool);

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(hasLive());
        assertEquals(localVersion, versionOf());
    }

    private static PeerConnectionPool emptyDigestPool() {
        final var pool = mock(PeerConnectionPool.class);
        try {
            when(pool.request(any(), any(), anyLong())).thenAnswer(_ -> {
                final var response = new ClusterMessage();
                response.setType(ClusterMessageType.DIGEST_ACK);
                final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
                payload.setDigest(List.of());
                response.setAntiEntropy(payload);
                return response;
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return pool;
    }

    @Test
    public void test_start_runs_periodic_sweep() throws Exception {
        final var config = Configuration.getInstance();
        final var originalEnabled = config.isClusterEnabled();
        final var originalInterval = config.getAntiEntropyIntervalMs();
        final var pool = emptyDigestPool();
        try {
            TestUtils.setPrivateField(config, "clusterEnabled", true);
            TestUtils.setPrivateField(config, "antiEntropyIntervalMs", 50L);
            injectPeer(pool);
            service.start();
            verify(pool, timeout(3000).atLeastOnce()).request(any(), any(), anyLong());
        } finally {
            service.stop();
            TestUtils.setPrivateField(config, "clusterEnabled", originalEnabled);
            TestUtils.setPrivateField(config, "antiEntropyIntervalMs", originalInterval);
        }
    }

    @Test
    public void test_start_is_noop_when_disabled() throws Exception {
        final var config = Configuration.getInstance();
        final var originalEnabled = config.isClusterEnabled();
        try {
            TestUtils.setPrivateField(config, "clusterEnabled", false);
            service.start();
            service.stop();
        } finally {
            TestUtils.setPrivateField(config, "clusterEnabled", originalEnabled);
        }
    }

    @Test
    public void test_gc_is_skipped_when_a_peer_did_not_answer() throws Exception {
        seed();
        final var config = Configuration.getInstance();
        final var originalRetention = config.getTombstoneRetentionMs();
        TestUtils.setPrivateField(config, "tombstoneRetentionMs", 1L);
        try {
            fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "old", HybridClock.pack(1L, 0));
            final var pool = mock(PeerConnectionPool.class);
            when(pool.request(any(), any(), anyLong())).thenThrow(new java.io.IOException("unreachable"));
            injectPeer(pool);

            service.reconcile(TestGlobals.DB, TestGlobals.COLL);

            assertTrue(fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).containsKey("old"),
                    "a tombstone must survive a round no peer answered: collecting it while partitioned is"
                            + " exactly when a peer still holding the document resurrects it");
        } finally {
            TestUtils.setPrivateField(config, "tombstoneRetentionMs", originalRetention);
        }
    }

    @Test
    public void test_gc_is_skipped_when_there_are_no_peers() throws Exception {
        seed();
        final var config = Configuration.getInstance();
        final var originalRetention = config.getTombstoneRetentionMs();
        TestUtils.setPrivateField(config, "tombstoneRetentionMs", 1L);
        try {
            fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "lonely", HybridClock.pack(1L, 0));
            final var self = node("self", 5000);
            final var membership = mock(MembershipService.class);
            when(membership.getSelf()).thenReturn(self);
            when(membership.membershipView()).thenReturn(new MembershipView(List.of(self)));
            TestUtils.setPrivateField(service, "membershipService", membership);

            service.reconcile(TestGlobals.DB, TestGlobals.COLL);

            assertTrue(fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).containsKey("lonely"),
                    "a node that can see nobody may itself be the partitioned side");
        } finally {
            TestUtils.setPrivateField(config, "tombstoneRetentionMs", originalRetention);
        }
    }

    @Test
    public void test_reconcile_garbage_collects_expired_tombstones() throws Exception {
        final var retention = Configuration.getInstance().getTombstoneRetentionMs();
        final var expired = HybridClock.pack(System.currentTimeMillis() - (2L * retention), 0);
        final var recent = HybridClock.pack(System.currentTimeMillis(), 0);
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "old", expired);
        fs.appendTombstone(TestGlobals.DB, TestGlobals.COLL, "recent", recent);
        injectPeer(emptyDigestPool());

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        final var tombstones = fs.readTombstones(TestGlobals.DB, TestGlobals.COLL);
        assertNull(tombstones.get("old"));
        assertEquals(recent, tombstones.get("recent"));
    }

    private String peerValueOf() {
        final var request = new org.techhouse.ops.req.FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("a");
        final var response = processor.processMessage(request);
        if (response instanceof org.techhouse.ops.resp.FindByIdResponse found && found.getObject() != null) {
            return found.getObject().get("v").asJsonNumber().getValue().toString();
        }
        return null;
    }

    private PeerConnectionPool peerAtEqualVersion(String peerNodeId, long version) throws Exception {
        final var peerDocument = new JsonObject();
        peerDocument.addProperty("_id", "a");
        peerDocument.addProperty("v", 9);
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var response = new ClusterMessage();
            final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
            if (message.getType() == ClusterMessageType.DIGEST) {
                response.setType(ClusterMessageType.DIGEST_ACK);
                payload.setDigest(List.of(new DigestEntry("a", version, false, peerNodeId)));
            } else {
                response.setType(ClusterMessageType.PULL_ACK);
                payload.setDocuments(List.of(peerDocument));
                payload.setVersions(List.of(Long.toString(version)));
            }
            response.setAntiEntropy(payload);
            return response;
        });
        return pool;
    }

    @Test
    public void test_equal_versions_converge_on_the_higher_node_id() throws Exception {
        seed();
        injectPeer(peerAtEqualVersion("zeta", versionOf()));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals("9", peerValueOf(),
                "a winner that wins only on the node-id tie-break is still a winner and must be pulled");
    }

    @Test
    public void test_both_directions_reach_the_same_state() throws Exception {
        seed();
        injectPeer(peerAtEqualVersion("aaa", versionOf()));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals("1", peerValueOf(),
                "the same total order run from the other side keeps this node's copy, so the two agree");
    }
}
