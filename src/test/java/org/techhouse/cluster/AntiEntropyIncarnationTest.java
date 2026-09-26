package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

public class AntiEntropyIncarnationTest {
    private static final long PEER_VERSION = 9_999_999L;
    private final AntiEntropyService service = IocContainer.get(AntiEntropyService.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
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

    private void seed() {
        final var object = new JsonObject();
        object.addProperty("_id", "a");
        object.addProperty("v", 1);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        processor.processMessage(request);
    }

    private void setLocalIncarnation(long incarnation) {
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(entry, "the test collection must be registered before its incarnation can be set");
        entry.setIncarnation(incarnation);
    }

    private boolean holdsLocally(String id) throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .anyMatch(entry -> entry.getValue().equals(id));
    }

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    private void injectPeer(PeerConnectionPool pool, NodeInfo peer) throws Exception {
        final var self = node("self", 5000);
        final var membership = mock(MembershipService.class);
        when(membership.getSelf()).thenReturn(self);
        when(membership.membershipView()).thenReturn(new MembershipView(List.of(self, peer)));
        TestUtils.setPrivateField(service, "membershipService", membership);
        TestUtils.setPrivateField(service, "pool", pool);
    }

    private PeerConnectionPool poolAnswering(AntiEntropyPayload digestAnswer, List<ClusterMessageType> sentTypes)
            throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            sentTypes.add(message.getType());
            final var response = new ClusterMessage();
            if (message.getType() == ClusterMessageType.PULL) {
                response.setType(ClusterMessageType.PULL_ACK);
                response.setAntiEntropy(pullAnswerFor(message.getAntiEntropy().getIds()));
            } else {
                response.setType(ClusterMessageType.DIGEST_ACK);
                response.setAntiEntropy(digestAnswer);
            }
            return response;
        });
        return pool;
    }

    private static AntiEntropyPayload pullAnswerFor(List<String> ids) {
        final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
        final var documents = new ArrayList<JsonObject>();
        final var versions = new ArrayList<String>();
        for (final var id : ids) {
            final var document = new JsonObject();
            document.addProperty("_id", id);
            document.addProperty("v", 2);
            documents.add(document);
            versions.add(Long.toString(PEER_VERSION));
        }
        payload.setDocuments(documents);
        payload.setVersions(versions);
        return payload;
    }

    private static AntiEntropyPayload digestNaming(String id) {
        final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
        payload.setDigest(List.of(new DigestEntry(id, PEER_VERSION, false, "peer", 24L)));
        return payload;
    }

    @Test
    public void test_a_digest_for_a_higher_peer_incarnation_describes_no_documents() throws Exception {
        seed();
        setLocalIncarnation(100L);

        final var payload = service.buildDigest(TestGlobals.DB, TestGlobals.COLL, null, 200L);

        assertTrue(payload.isStaleIncarnation(),
                "documents held under an incarnation the cluster has since re-created are dead and must not be"
                        + " described to a peer");
        assertNull(payload.getDigest());
        assertEquals(100L, payload.incarnationValue());
    }

    @Test
    public void test_a_digest_for_a_lower_peer_incarnation_describes_no_documents() throws Exception {
        seed();
        setLocalIncarnation(200L);

        final var payload = service.buildDigest(TestGlobals.DB, TestGlobals.COLL, null, 100L);

        assertTrue(payload.isStaleIncarnation(), "a peer asking under a dead incarnation must be told so rather"
                + " than handed the live one's documents");
        assertNull(payload.getDigest());
    }

    @Test
    public void test_a_matching_incarnation_still_describes_every_document() throws Exception {
        seed();
        setLocalIncarnation(100L);

        final var payload = service.buildDigest(TestGlobals.DB, TestGlobals.COLL, null, 100L);

        assertFalse(payload.isStaleIncarnation());
        assertNotNull(payload.getDigest());
        assertTrue(payload.getDigest().stream().anyMatch(entry -> entry.getId().equals("a")));
        assertEquals(100L, payload.incarnationValue());
    }

    @Test
    public void test_a_zero_incarnation_on_either_side_is_treated_as_unknown() throws Exception {
        seed();
        setLocalIncarnation(0L);

        final var peerKnows = service.buildDigest(TestGlobals.DB, TestGlobals.COLL, null, 500L);

        assertFalse(peerKnows.isStaleIncarnation(), "an entry written before the incarnation field existed reads as"
                + " zero and must be adopted, never quarantined");
        assertNotNull(peerKnows.getDigest());

        setLocalIncarnation(500L);
        final var peerDoesNot = service.buildDigest(TestGlobals.DB, TestGlobals.COLL, null, 0L);

        assertFalse(peerDoesNot.isStaleIncarnation());
        assertNotNull(peerDoesNot.getDigest());
    }

    @Test
    public void test_a_pull_for_a_mismatched_incarnation_returns_no_documents() throws Exception {
        seed();
        setLocalIncarnation(100L);

        final var payload = service.buildPull(TestGlobals.DB, TestGlobals.COLL, List.of("a"), 200L);

        assertTrue(payload.isStaleIncarnation());
        assertNull(payload.getDocuments());
    }

    @Test
    public void test_a_pull_for_a_matching_incarnation_still_returns_the_document() throws Exception {
        seed();
        setLocalIncarnation(100L);

        final var payload = service.buildPull(TestGlobals.DB, TestGlobals.COLL, List.of("a"), 100L);

        assertFalse(payload.isStaleIncarnation());
        assertEquals(1, payload.getDocuments().size());
    }

    @Test
    public void test_reconcile_skips_a_peer_that_reports_a_stale_incarnation() throws Exception {
        seed();
        final var answer = digestNaming("resurrect");
        answer.setStaleIncarnation(true);
        final var sentTypes = new CopyOnWriteArrayList<ClusterMessageType>();
        injectPeer(poolAnswering(answer, sentTypes), node("peer", 5001));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertFalse(sentTypes.contains(ClusterMessageType.PULL),
                "a peer that answered staleIncarnation must never be pulled from");
        assertFalse(holdsLocally("resurrect"),
                "documents belonging to a dropped incarnation must not be seeded into the live one");
    }

    @Test
    public void test_reconcile_skips_a_peer_whose_digest_reports_a_different_incarnation() throws Exception {
        seed();
        setLocalIncarnation(200L);
        final var answer = digestNaming("resurrect");
        answer.setIncarnationValue(100L);
        final var sentTypes = new CopyOnWriteArrayList<ClusterMessageType>();
        injectPeer(poolAnswering(answer, sentTypes), node("peer", 5001));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertFalse(sentTypes.contains(ClusterMessageType.PULL));
        assertFalse(holdsLocally("resurrect"));
    }

    @Test
    public void test_reconcile_pulls_from_a_peer_whose_incarnation_matches() throws Exception {
        seed();
        setLocalIncarnation(200L);
        final var answer = digestNaming("wanted");
        answer.setIncarnationValue(200L);
        final var sentTypes = new CopyOnWriteArrayList<ClusterMessageType>();
        injectPeer(poolAnswering(answer, sentTypes), node("peer", 5001));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(sentTypes.contains(ClusterMessageType.PULL),
                "an ordinary catch-up must still pull the ids this node lacks");
        assertTrue(holdsLocally("wanted"), "the pulled document must actually be applied");
    }

    @Test
    public void test_reconcile_skips_a_peer_that_is_still_admin_syncing() throws Exception {
        seed();
        final var sentTypes = new CopyOnWriteArrayList<ClusterMessageType>();
        final var peer = node("peer", 5001);
        peer.setAdminSyncing(true);
        injectPeer(poolAnswering(digestNaming("resurrect"), sentTypes), peer);

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(sentTypes.isEmpty(),
                "a peer that has not conformed to the admin snapshot yet must not be asked for a digest at all");
        assertFalse(holdsLocally("resurrect"));
    }
}
