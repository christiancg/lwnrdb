package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.Tx2pcRecovery;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class Tx2pcRecoveryTest {
    private static final String SELF_ADDRESS = "127.0.0.1:5000";
    private final Configuration config = Configuration.getInstance();
    private final Tx2pcRecovery recovery = IocContainer.get(Tx2pcRecovery.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private boolean origEnabled;
    private PeerConnectionPool origPool;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origPool = TestUtils.getPrivateField(recovery, "pool", PeerConnectionPool.class);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        TestUtils.setPrivateField(membershipService, "members",
                new ConcurrentHashMap<>(java.util.Map.of("self", node())));
        TestUtils.setPrivateField(membershipService, "self", node());
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(membershipService.membershipView());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(recovery, "pool", origPool);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    // Seeds a durable one-op prepared slice (a SAVE of the given id) plus its PREPARED marker.
    private void seedPreparedSlice(String dtxId, String id) throws Exception {
        seedPreparedSlice(dtxId, id, SELF_ADDRESS);
    }

    private void seedPreparedSlice(String dtxId, String id, String coordinatorAddress) throws Exception {
        seedPreparedSlice(dtxId, id, coordinatorAddress, List.of(coordinatorAddress));
    }

    private void seedPreparedSlice(String dtxId, String id, String coordinatorAddress, List<String> participants)
            throws Exception {
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", 0,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, obj));
        Tx2pcLog.recordParticipantPrepared(dtxId, coordinatorAddress, participants,
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
    }

    private OperationStatus findStatus(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    @Test
    public void test_committed_transaction_is_replayed() throws Exception {
        final var dtxId = "33333333-3333-3333-3333-333333333333";
        seedPreparedSlice(dtxId, "rec-commit");
        Tx2pcLog.recordCoordinatorCommit(dtxId, List.of(SELF_ADDRESS));

        recovery.recover();

        assertEquals(OperationStatus.OK, findStatus("rec-commit"));
        assertFalse(Tx2pcLog.isPrepared(dtxId));
        assertFalse(Tx2pcLog.isCommitted(dtxId));
    }

    @Test
    public void test_undecided_transaction_is_presumed_abort() throws Exception {
        final var dtxId = "44444444-4444-4444-4444-444444444444";
        seedPreparedSlice(dtxId, "rec-abort");
        // No coordinator marker → presumed abort.

        recovery.recover();

        assertEquals(OperationStatus.NOT_FOUND, findStatus("rec-abort"));
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }

    @Test
    public void test_committed_transaction_with_mixed_ops_replayed() throws Exception {
        // A doc to be deleted by the recovered transaction.
        final var seed = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var seedObj = new JsonObject();
        seedObj.add("_id", new JsonString("mix-del"));
        seed.setObject(seedObj);
        seed.set_id("mix-del");
        processor.processMessage(seed);

        final var dtxId = "77777777-7777-7777-7777-777777777777";
        final var save = new JsonObject();
        save.add("_id", new JsonString("mix-save"));
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", 0,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, save));
        final var bulkPayload = new JsonObject();
        final var array = new org.techhouse.ejson.elements.JsonArray();
        final var bulkObj = new JsonObject();
        bulkObj.add("_id", new JsonString("mix-bulk"));
        array.add(bulkObj);
        bulkPayload.add("objects", array);
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", 1,
                AdminTransactionEntry.OP_TYPE_BULK_SAVE, TestGlobals.DB, TestGlobals.COLL, bulkPayload));
        final var delPayload = new JsonObject();
        delPayload.add("_id", new JsonString("mix-del"));
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", 2,
                AdminTransactionEntry.OP_TYPE_DELETE, TestGlobals.DB, TestGlobals.COLL, delPayload));
        Tx2pcLog.recordParticipantPrepared(dtxId, SELF_ADDRESS, List.of(SELF_ADDRESS),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        Tx2pcLog.recordCoordinatorCommit(dtxId, List.of(SELF_ADDRESS));

        recovery.recover();

        assertEquals(OperationStatus.OK, findStatus("mix-save"));
        assertEquals(OperationStatus.OK, findStatus("mix-bulk"));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("mix-del"));
    }

    @Test
    public void test_unreachable_coordinator_leaves_in_doubt() throws Exception {
        final var dtxId = "88888888-8888-8888-8888-888888888888";
        seedPreparedSlice(dtxId, "rec-indoubt", "127.0.0.1:1");

        recovery.recover();

        // Coordinator unreachable → decision unknown → still prepared, not applied.
        org.junit.jupiter.api.Assertions.assertTrue(Tx2pcLog.isPrepared(dtxId));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("rec-indoubt"));
    }

    @Test
    public void test_coordinator_redrive_to_unreachable_keeps_marker() throws Exception {
        final var dtxId = "99999999-9999-9999-9999-999999999999";
        Tx2pcLog.recordCoordinatorCommit(dtxId, List.of("127.0.0.1:1"));

        recovery.recover();

        // The lone participant is unreachable, so the commit decision is kept for a later retry.
        org.junit.jupiter.api.Assertions.assertTrue(Tx2pcLog.isCommitted(dtxId));
    }

    @Test
    public void test_recover_is_noop_when_clustering_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        final var dtxId = "55555555-5555-5555-5555-555555555555";
        seedPreparedSlice(dtxId, "rec-noop");
        recovery.recover();
        // Untouched: still prepared, not applied.
        assertTrue(Tx2pcLog.isPrepared(dtxId));
    }

    // Injects a pool where the coordinator (port 1) is unreachable and the peer (port 2) reports the given
    // status — the cooperative-termination scenario.
    private void injectCooperativePool(Tx2pcLog.Status peerStatus) throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final NodeAddress address = invocation.getArgument(0);
            if (address.getPort() == 1) {
                throw new java.io.IOException("coordinator unreachable");
            }
            final var response = new ClusterMessage();
            response.setType(ClusterMessageType.TX_STATUS_ACK);
            response.setTxStatus(peerStatus.name());
            return response;
        });
        TestUtils.setPrivateField(recovery, "pool", pool);
    }

    private String seedCrossNodePrepared(String id) throws Exception {
        final var dtxId = java.util.UUID.randomUUID().toString();
        seedPreparedSlice(dtxId, id, "127.0.0.1:1", List.of("127.0.0.1:1", "127.0.0.1:2"));
        return dtxId;
    }

    @Test
    public void test_cooperative_termination_commits_when_peer_committed() throws Exception {
        injectCooperativePool(Tx2pcLog.Status.COMMITTED);
        final var dtxId = seedCrossNodePrepared("coop-commit");
        recovery.recover();
        assertEquals(OperationStatus.OK, findStatus("coop-commit"));
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }

    @Test
    public void test_cooperative_termination_aborts_when_peer_aborted() throws Exception {
        injectCooperativePool(Tx2pcLog.Status.ABORTED);
        final var dtxId = seedCrossNodePrepared("coop-abort");
        recovery.recover();
        assertEquals(OperationStatus.NOT_FOUND, findStatus("coop-abort"));
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }

    @Test
    public void test_cooperative_termination_stays_in_doubt_when_peer_prepared() throws Exception {
        injectCooperativePool(Tx2pcLog.Status.PREPARED);
        final var dtxId = seedCrossNodePrepared("coop-indoubt");
        recovery.recover();
        assertEquals(OperationStatus.NOT_FOUND, findStatus("coop-indoubt"));
        assertTrue(Tx2pcLog.isPrepared(dtxId));
    }

    @Test
    public void test_start_runs_periodic_sweep_and_warns() throws Exception {
        final var origInterval = config.getAntiEntropyIntervalMs();
        final var origDead = config.getDeadTimeoutMs();
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenThrow(new java.io.IOException("unreachable"));
        TestUtils.setPrivateField(recovery, "pool", pool);
        seedPreparedSlice("dtx-sweep", "sweep-doc", "127.0.0.1:1");
        TestUtils.setPrivateField(config, "antiEntropyIntervalMs", 50L);
        TestUtils.setPrivateField(config, "deadTimeoutMs", -1L);
        try {
            recovery.start();
            verify(pool, timeout(3000).atLeastOnce()).request(any(), any(), anyLong());
        } finally {
            recovery.stop();
            TestUtils.setPrivateField(config, "antiEntropyIntervalMs", origInterval);
            TestUtils.setPrivateField(config, "deadTimeoutMs", origDead);
        }
    }

    @Test
    public void test_start_is_noop_when_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        recovery.start();
        recovery.stop();
    }
}
