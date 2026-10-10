package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AntiEntropyShutdownTest {
    private static final String ID = "a";
    private final AntiEntropyService service = IocContainer.get(AntiEntropyService.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private MembershipService realMembership;
    private PeerConnectionPool realPool;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        realMembership = TestUtils.getPrivateField(service, "membershipService", MembershipService.class);
        realPool = TestUtils.getPrivateField(service, "pool", PeerConnectionPool.class);
    }

    @AfterEach
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void tearDown() throws Exception {
        Thread.interrupted();
        TestUtils.setPrivateField(service, "membershipService", realMembership);
        TestUtils.setPrivateField(service, "pool", realPool);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void seed() {
        final var obj = new JsonObject();
        obj.addProperty("_id", ID);
        obj.addProperty("v", 1);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(obj);
        processor.processMessage(request);
    }

    private boolean hasLive() throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .anyMatch(e -> e.getValue().equals(ID));
    }

    private PeerConnectionPool peerInterruptingOnDigest(DigestEntry entry) throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var response = new ClusterMessage();
            final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
            if (message.getType() == ClusterMessageType.DIGEST) {
                Thread.currentThread().interrupt();
                response.setType(ClusterMessageType.DIGEST_ACK);
                payload.setDigest(List.of(entry));
            } else {
                final var doc = new JsonObject();
                doc.addProperty("_id", ID);
                doc.addProperty("v", 9);
                response.setType(ClusterMessageType.PULL_ACK);
                payload.setDocuments(List.of(doc));
                payload.setVersions(List.of(Long.toString(entry.versionValue())));
            }
            response.setAntiEntropy(payload);
            return response;
        });
        final var self = node("self", 5000);
        final var peer = node("peer", 5001);
        final var membership = mock(MembershipService.class);
        when(membership.getSelf()).thenReturn(self);
        when(membership.membershipView()).thenReturn(new MembershipView(List.of(self, peer)));
        TestUtils.setPrivateField(service, "membershipService", membership);
        TestUtils.setPrivateField(service, "pool", pool);
        return pool;
    }

    @Test
    public void test_a_sweep_interrupted_mid_digest_pulls_nothing() throws Exception {
        final var pool = peerInterruptingOnDigest(new DigestEntry(ID, 1000L, false));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);
        final var stillInterrupted = Thread.interrupted();

        assertTrue(stillInterrupted, "the shutdown interrupt must survive the digest round");
        assertFalse(hasLive(), "an interrupted sweep must not apply a pull after the shutdown drained the queue");
        verify(pool, times(1)).request(any(), any(), anyLong());
    }

    @Test
    public void test_a_sweep_interrupted_mid_digest_deletes_nothing() throws Exception {
        seed();
        peerInterruptingOnDigest(
                new DigestEntry(ID, HybridClock.pack(System.currentTimeMillis() + 1_000_000L, 0), true));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);
        final var stillInterrupted = Thread.interrupted();

        assertTrue(stillInterrupted, "the shutdown interrupt must survive the digest round");
        assertTrue(hasLive(), "an interrupted sweep must not apply a delete after the shutdown drained the queue");
    }

    @Test
    public void test_an_interrupted_sweep_visits_no_collection() throws Exception {
        final var pool = peerInterruptingOnDigest(new DigestEntry(ID, 1000L, false));
        final var sweep = AntiEntropyService.class.getDeclaredMethod("reconcileAllCollections");
        sweep.setAccessible(true);
        Thread.currentThread().interrupt();

        sweep.invoke(service);
        final var stillInterrupted = Thread.interrupted();

        assertTrue(stillInterrupted);
        verify(pool, never()).request(any(), any(), anyLong());
    }
}
