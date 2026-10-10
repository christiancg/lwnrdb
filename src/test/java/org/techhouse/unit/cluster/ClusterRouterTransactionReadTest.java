package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterRouterTransactionReadTest {
    private static final String JOINED = "joined";
    private static final String UNREACHABLE_PEER = "127.0.0.1:1";
    private static final String OTHER_UNREACHABLE_PEER = "127.0.0.1:2";
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;
    private UUID client;

    @BeforeEach
    public void setUp() throws Exception {
        origEnabled = config.isClusterEnabled();
        resetOwnership();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        client = clientTracker.registerForwardedClient("someuser");
    }

    @AfterEach
    public void tearDown() throws Exception {
        clientTracker.removeById(client);
        pool.closeAll();
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        resetOwnership();
    }

    private void resetOwnership() throws Exception {
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
    }

    private static String id(String collection) {
        return Cache.getCollectionIdentifier(TestGlobals.DB, collection);
    }

    private static AggregateRequest joinRead() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new JoinAggregationStep(JOINED, "ref", "k", "j")));
        return request;
    }

    private String route(OperationRequest request) {
        return router.forward(request, "{}", true, "someuser", client);
    }

    private void wrote(String collection, String holder) {
        clientTracker.recordTransactionWrite(client, id(collection), holder);
    }

    @Test
    public void test_read_with_no_written_collection_keeps_primary_routing() {
        assertNull(route(joinRead()));
    }

    @Test
    public void test_join_whose_target_was_written_locally_runs_locally() {
        wrote(JOINED, ClusterRouter.LOCAL_HOLDER);
        assertNull(route(joinRead()));
    }

    @Test
    public void test_join_whose_target_was_written_on_a_participant_is_forwarded_there() {
        wrote(JOINED, UNREACHABLE_PEER);
        final var response = route(joinRead());
        assertNotNull(response, "the read must go to the node holding the join target's buffered writes");
        assertTrue(response.contains("503-4"), response);
    }

    @Test
    public void test_join_spanning_a_local_and_a_remote_holder_is_refused_with_421_3() {
        wrote(TestGlobals.COLL, ClusterRouter.LOCAL_HOLDER);
        wrote(JOINED, UNREACHABLE_PEER);
        final var response = route(joinRead());
        assertNotNull(response);
        assertTrue(response.contains("421-3"), response);
    }

    @Test
    public void test_join_spanning_two_remote_holders_is_refused() {
        wrote(TestGlobals.COLL, OTHER_UNREACHABLE_PEER);
        wrote(JOINED, UNREACHABLE_PEER);
        final var response = route(joinRead());
        assertNotNull(response);
        assertTrue(response.contains("421-3"), response);
    }

    @Test
    public void test_a_collection_written_on_two_nodes_refuses_even_a_single_collection_read() {
        wrote(TestGlobals.COLL, ClusterRouter.LOCAL_HOLDER);
        wrote(TestGlobals.COLL, UNREACHABLE_PEER);
        final var response = route(new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL));
        assertNotNull(response);
        assertTrue(response.contains("421-3"), response);
    }

    @Test
    public void test_find_by_id_routes_to_the_holder_of_its_collection() {
        wrote(TestGlobals.COLL, UNREACHABLE_PEER);
        final var response = route(new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL));
        assertNotNull(response);
        assertTrue(response.contains("503-4"), response);
    }

    @Test
    public void test_find_by_id_ignores_holders_of_other_collections() {
        wrote(JOINED, UNREACHABLE_PEER);
        assertNull(route(new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL)));
    }

    @Test
    public void test_refusal_does_not_clear_the_transaction() {
        clientTracker.addTransactionParticipant(client, UNREACHABLE_PEER);
        wrote(TestGlobals.COLL, ClusterRouter.LOCAL_HOLDER);
        wrote(JOINED, UNREACHABLE_PEER);

        route(joinRead());

        assertEquals(Set.of(UNREACHABLE_PEER), clientTracker.transactionParticipants(client));
        assertEquals(Set.of(ClusterRouter.LOCAL_HOLDER, UNREACHABLE_PEER),
                clientTracker.transactionWriteHolders(client, List.of(id(TestGlobals.COLL), id(JOINED))));
    }

    @Test
    public void test_a_locally_buffered_write_records_the_local_holder() {
        assertNull(route(new SaveRequest(TestGlobals.DB, TestGlobals.COLL)));
        assertEquals(Set.of(ClusterRouter.LOCAL_HOLDER),
                clientTracker.transactionWriteHolders(client, List.of(id(TestGlobals.COLL))));
    }

    @Test
    public void test_a_forwarded_write_records_its_owner_as_the_holder() throws Exception {
        final var cluster = new ClusterTestHarness();
        cluster.start();
        try {
            cluster.configureMembership(2, ClusterTestHarness.node("self", 19990), ClusterTestHarness.node("other", 1));
            final var remote = collectionOwnedByOther();
            final var save = new SaveRequest(TestGlobals.DB, remote);

            final var response = route(save);

            assertNotNull(response);
            assertEquals(Set.of(UNREACHABLE_PEER), clientTracker.transactionWriteHolders(client, List.of(id(remote))));
        } finally {
            cluster.stop();
        }
    }

    private String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var collection = "routed-" + i;
            if (!ownership.isOwner(TestGlobals.DB, collection)) {
                return collection;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }
}
