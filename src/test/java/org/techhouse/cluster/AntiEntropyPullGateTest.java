package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AntiEntropyPullGateTest {
    private static final String ID = "a";
    private static final String HIGHER_NODE_ID = "zeta";
    private static final long PEER_VERSION = 5L;
    private static final int BEYOND_ONE_BATCH = AntiEntropyService.PULL_BATCH_SIZE + 1;

    private final AntiEntropyService service = IocContainer.get(AntiEntropyService.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final AtomicInteger pulls = new AtomicInteger();
    private MembershipService realMembership;
    private PeerConnectionPool realPool;

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
        final var self = new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
        final var peer = new NodeInfo(HIGHER_NODE_ID, "127.0.0.1", 5001, NodeState.ALIVE, 1L, 1L);
        final var membership = mock(MembershipService.class);
        when(membership.getSelf()).thenReturn(self);
        when(membership.membershipView()).thenReturn(new MembershipView(List.of(self, peer)));
        TestUtils.setPrivateField(service, "membershipService", membership);
        TestUtils.setPrivateField(service, "pool", pool);
    }

    private static JsonObject document(String id, int value) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("v", value);
        return object;
    }

    private void seedLocal() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(ID, 1));
        processor.processMessage(request);
    }

    private PkIndexEntry localEntry() throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(entry -> entry.getValue().equals(ID)).findFirst().orElseThrow();
    }

    private int localCount() throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).size();
    }

    private PeerConnectionPool peer(List<DigestEntry> digest, int failingPull) throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var response = new ClusterMessage();
            final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
            if (message.getType() == ClusterMessageType.DIGEST) {
                response.setType(ClusterMessageType.DIGEST_ACK);
                payload.setDigest(digest);
            } else if (pulls.incrementAndGet() == failingPull) {
                response.setType(ClusterMessageType.ERROR);
                response.setErrorMessage("injected pull failure");
                return response;
            } else {
                response.setType(ClusterMessageType.PULL_ACK);
                final var documents = new ArrayList<JsonObject>();
                final var versions = new ArrayList<String>();
                for (final var id : message.getAntiEntropy().getIds()) {
                    documents.add(document(id, 9));
                    versions.add(Long.toString(PEER_VERSION));
                }
                payload.setDocuments(documents);
                payload.setVersions(versions);
            }
            response.setAntiEntropy(payload);
            return response;
        });
        return pool;
    }

    private static List<DigestEntry> remoteOnlyDigestBeyondOneBatch() {
        final var digest = new ArrayList<DigestEntry>();
        for (var i = 0; i < BEYOND_ONE_BATCH; i++) {
            digest.add(new DigestEntry("remote-" + i, PEER_VERSION, false, HIGHER_NODE_ID, 20L));
        }
        return digest;
    }

    @Test
    public void test_an_equal_version_equal_length_copy_is_not_pulled() throws Exception {
        seedLocal();
        final var local = localEntry();
        injectPeer(peer(List.of(new DigestEntry(ID, local.getVersion(), false, HIGHER_NODE_ID, local.getLength())), 0));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(0, pulls.get(), "an identical copy at the same version must not be re-pulled and rewritten");
    }

    @Test
    public void test_an_equal_version_different_length_copy_is_still_pulled_from_the_higher_node_id() throws Exception {
        seedLocal();
        final var local = localEntry();
        injectPeer(peer(List.of(new DigestEntry(ID, local.getVersion(), false, HIGHER_NODE_ID, local.getLength() + 1)),
                0));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(1, pulls.get(), "two different copies at one version still converge on the higher node id");
    }

    @Test
    public void test_pulls_are_sent_in_batches() throws Exception {
        injectPeer(peer(remoteOnlyDigestBeyondOneBatch(), 0));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(2, pulls.get(), "one unbounded PULL can outlast the ack timeout on a large collection");
        assertEquals(BEYOND_ONE_BATCH, localCount(), "every batch must be applied");
    }

    @Test
    public void test_a_failed_batch_does_not_stop_the_others() throws Exception {
        injectPeer(peer(remoteOnlyDigestBeyondOneBatch(), 1));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(2, pulls.get());
        assertEquals(1, localCount(), "the batch after the failed one must still be pulled and applied");
    }
}
