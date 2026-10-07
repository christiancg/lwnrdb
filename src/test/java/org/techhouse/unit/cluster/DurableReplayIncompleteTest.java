package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.Tx2pcRecovery;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.DurableReplayIncompleteException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.TransactionRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DurableReplayIncompleteTest {
    private static final String SELF_ADDRESS = "127.0.0.1:5000";
    private static final String ABSENT_COLL = "late-collection";
    private final Configuration config = Configuration.getInstance();
    private final Tx2pcRecovery recovery = IocContainer.get(Tx2pcRecovery.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private volatile boolean origEnabled;
    private volatile long origAckTimeoutMs;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
    }

    private static String collectionId() {
        return Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origAckTimeoutMs = config.getReplicationAckTimeoutMs();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 2000L);
        TestUtils.setPrivateField(membershipService, "members",
                new ConcurrentHashMap<>(java.util.Map.of("self", node())));
        TestUtils.setPrivateField(membershipService, "self", node());
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(membershipService.membershipView());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origAckTimeoutMs);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminTransactionEntry saveOp(String txId, String collName, JsonObject payload) {
        return new AdminTransactionEntry(txId, "client", 0, AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB,
                collName, payload);
    }

    private static AdminTransactionEntry unappliableOp(String txId) {
        return saveOp(txId, ABSENT_COLL, document("into-a-missing-collection"));
    }

    private static JsonObject document(String id) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        return obj;
    }

    private static String seedUnappliablePreparedSlice() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        AdminOperationHelper.saveTransactionOp(unappliableOp(dtxId));
        Tx2pcLog.recordParticipantPrepared(dtxId, SELF_ADDRESS, List.of(SELF_ADDRESS), List.of(collectionId()));
        return dtxId;
    }

    private OperationStatus findStatus(String collName, String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, collName);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private void createAbsentCollection() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, ABSENT_COLL)).getStatus());
    }

    private OperationStatus saveFromAnotherThread() throws Exception {
        final var id = "after-recovery";
        final var status = new AtomicReference<OperationStatus>();
        final var writer = Thread.ofVirtual().start(() -> {
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObject(document(id));
            request.set_id(id);
            status.set(processor.processMessage(request).getStatus());
        });
        assertTrue(writer.join(java.time.Duration.ofSeconds(10)), "a write from another thread must not park");
        return status.get();
    }

    @Test
    public void test_a_prepared_slice_whose_ops_never_apply_reports_an_incomplete_commit() throws Exception {
        final var dtxId = seedUnappliablePreparedSlice();

        final var thrown = assertThrows(DurableReplayIncompleteException.class,
                () -> TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collectionId()), 0L));

        assertTrue(thrown.getMessage().contains(dtxId));
        assertTrue(Tx2pcLog.isPrepared(dtxId), "the participant marker is kept for the next attempt");
        assertFalse(Tx2pcLog.sliceOpIds(dtxId).isEmpty(), "the slice's op records are kept for the next attempt");
    }

    @Test
    public void test_a_local_commit_whose_ops_never_apply_reports_an_incomplete_commit() throws Exception {
        final var txId = UUID.randomUUID().toString();
        final var unappliable = unappliableOp(txId);
        AdminOperationHelper.saveTransactionOp(unappliable);
        TxCommitLog.recordLocalCommit(txId, List.of(unappliable.get_id()), List.of(collectionId()));

        assertThrows(DurableReplayIncompleteException.class,
                () -> TransactionOperationHelper.commitLocalFromDurable(txId, List.of(collectionId())));

        assertTrue(TxCommitLog.isLocallyCommitted(txId), "the local-commit marker is kept for the next attempt");
    }

    @Test
    public void test_resolving_a_replayable_slice_still_completes_normally() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        AdminOperationHelper.saveTransactionOp(saveOp(dtxId, TestGlobals.COLL, document("replayable")));
        Tx2pcLog.recordParticipantPrepared(dtxId, SELF_ADDRESS, List.of(SELF_ADDRESS), List.of(collectionId()));

        assertDoesNotThrow(() -> TransactionRecovery.resolveFromDurable(dtxId, true, 0L));

        assertFalse(Tx2pcLog.isPrepared(dtxId));
        assertEquals(OperationStatus.OK, findStatus(TestGlobals.COLL, "replayable"));
    }

    @Test
    public void test_coordinator_recovery_keeps_its_marker_while_its_own_slice_is_incomplete() throws Exception {
        final var dtxId = seedUnappliablePreparedSlice();
        Tx2pcLog.recordCoordinatorCommit(dtxId, null, List.of(SELF_ADDRESS));

        assertDoesNotThrow(recovery::recover);
        assertDoesNotThrow(recovery::recover);

        assertTrue(Tx2pcLog.isCommitted(dtxId),
                "a coordinator whose own slice did not apply must not forget that the transaction committed");
        assertTrue(Tx2pcLog.isPrepared(dtxId), "the incomplete slice stays in doubt rather than being aborted");
    }

    @Test
    public void test_an_incomplete_slice_commits_once_its_ops_can_apply_and_frees_its_collection() throws Exception {
        final var dtxId = seedUnappliablePreparedSlice();
        Tx2pcLog.recordCoordinatorCommit(dtxId, null, List.of(SELF_ADDRESS));
        recovery.recover();
        assertTrue(Tx2pcLog.isCommitted(dtxId));

        createAbsentCollection();
        recovery.recover();

        assertFalse(Tx2pcLog.isPrepared(dtxId), "the retried replay finished the slice");
        assertFalse(Tx2pcLog.isCommitted(dtxId), "the coordinator marker goes once every slice committed");
        assertEquals(OperationStatus.OK, findStatus(ABSENT_COLL, "into-a-missing-collection"));
        assertEquals(OperationStatus.OK, saveFromAnotherThread(),
                "every lock hold the failed rounds kept is released once the slice finishes");
    }

    @Test
    public void test_a_replay_that_failed_at_startup_is_finished_by_a_later_round() throws Exception {
        final var dtxId = seedUnappliablePreparedSlice();
        Tx2pcLog.recordCoordinatorCommit(dtxId, null, List.of(SELF_ADDRESS));
        recovery.recoverNow();
        assertTrue(Tx2pcLog.isPrepared(dtxId), "the first round could not apply the slice");

        createAbsentCollection();
        recovery.recoverNow();

        assertFalse(Tx2pcLog.isPrepared(dtxId), "a later round finishes the slice the startup round kept locked");
        assertEquals(OperationStatus.OK, findStatus(ABSENT_COLL, "into-a-missing-collection"));
        assertEquals(OperationStatus.OK, saveFromAnotherThread());
    }

    @Test
    public void test_a_half_applied_commit_through_a_live_session_is_not_reported_resolved() throws Exception {
        final var sessionId = "half-applied-session";
        final var session = clientTracker.registerTxSession(sessionId, "tester", "edge-node");
        try {
            session.submit(() -> TransactionOperationHelper.start(session.clientId())).get(10, TimeUnit.SECONDS);
            final var transaction = clientTracker.getActiveTransaction(session.clientId());
            final var dtxId = transaction.getTransactionId().toString();
            final var unappliable = unappliableOp(dtxId);
            AdminOperationHelper.saveTransactionOp(unappliable);
            transaction.getBufferedOpIds().add(unappliable.get_id());
            Tx2pcLog.recordParticipantPrepared(dtxId, SELF_ADDRESS, List.of(SELF_ADDRESS), List.of(collectionId()));

            final var thrown = assertThrows(DurableReplayIncompleteException.class,
                    () -> TwoPhaseParticipant.resolveFromDurable(dtxId, true, 5000L));

            assertTrue(thrown.getMessage().contains(dtxId));
            assertTrue(Tx2pcLog.isPrepared(dtxId), "the fenced slice keeps its marker");
            assertNotNull(clientTracker.txSession(sessionId), "the session naming the fenced slice's locks is kept");
        } finally {
            clientTracker.removeTxSession(sessionId);
        }
    }
}
