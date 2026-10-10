package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.FencedTriggerRuns;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerConsumingSliceTest {
    private static final Configuration configuration = Configuration.getInstance();
    private static final String COORDINATOR = "127.0.0.1:9000";
    private static final String RUN_ID = "run-consumed-by-a-slice";

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final CopyOnWriteArrayList<TriggerEvent> captured = new CopyOnWriteArrayList<>();
    private String txId;

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        triggerExecutor.stop();
        triggerExecutor.start(captured::add);
        saveLiveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        txId = null;
    }

    @AfterEach
    void clearMarkers() throws Exception {
        if (txId != null) {
            Tx2pcLog.deleteParticipantMarker(txId);
            TxCommitLog.clearLocalCommit(txId);
            AdminOperationHelper.deleteTransactionOps(Tx2pcLog.sliceOpIds(txId));
        }
    }

    private static void saveLiveDocument() {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString("live"));
        object.add("value", new JsonString("v"));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id("live");
        IocContainer.get(OperationProcessor.class).processMessage(request);
    }

    private static void recordRun(String nodeId) throws Exception {
        AdminOperationHelper.saveTriggerRun(
                new AdminTriggerRunEntry(RUN_ID, 0L, nodeId, TestGlobals.DB, TestGlobals.COLL, "audit", "noop",
                        EventType.UPDATED, false, "admin", 0, System.currentTimeMillis(), List.of("live"), List.of()));
    }

    private static List<String> collections() {
        return List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));
    }

    private List<String> crashWithASliceConsuming(String runId) throws Exception {
        final var clientId = clientTracker.registerForwardedClient("admin");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        TransactionOperationHelper.bufferTriggerRunConsume(transaction, runId);
        txId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        clientTracker.removeById(clientId);
        TestUtils.releaseAllLocks();
        return opIds;
    }

    private void crashPrepared(String runId) throws Exception {
        crashWithASliceConsuming(runId);
        Tx2pcLog.recordParticipantPrepared(txId, COORDINATOR, List.of(COORDINATOR), collections());
    }

    private List<String> queuedRunIds() throws InterruptedException {
        Thread.sleep(50L);
        return captured.stream().map(TriggerEvent::getRunId).toList();
    }

    @Test
    public void test_a_run_consumed_by_a_prepared_slice_is_left_to_that_slice() throws Exception {
        recordRun(TriggerRunLog.currentNodeId());
        crashPrepared(RUN_ID);

        assertFalse(TriggerRunRecovery.startupRunIds().contains(RUN_ID),
                "replaying it at startup and again when the slice commits would run the trigger body twice");
    }

    @Test
    public void test_a_run_consumed_by_a_surviving_local_commit_is_left_to_that_slice() throws Exception {
        recordRun(TriggerRunLog.currentNodeId());
        final var opIds = crashWithASliceConsuming(RUN_ID);
        TxCommitLog.recordLocalCommit(txId, opIds, collections());

        assertFalse(TriggerRunRecovery.startupRunIds().contains(RUN_ID));
    }

    @Test
    public void test_a_run_no_slice_consumes_is_still_replayed() throws Exception {
        recordRun(TriggerRunLog.currentNodeId());
        crashPrepared("some-other-run");

        assertTrue(TriggerRunRecovery.startupRunIds().contains(RUN_ID));
    }

    @Test
    public void test_a_committed_slice_consumes_the_run_and_nothing_replays_it() throws Exception {
        recordRun(TriggerRunLog.currentNodeId());
        crashPrepared(RUN_ID);
        final var startupRuns = TriggerRunRecovery.startupRunIds();

        TwoPhaseParticipant.commitPreparedFromDurable(txId, collections(), 0L);
        TriggerRunRecovery.recoverLocal(startupRuns);

        assertFalse(TriggerRunLog.pendingRunIds().contains(RUN_ID));
        assertTrue(queuedRunIds().isEmpty());
    }

    @Test
    public void test_an_aborted_slice_requeues_the_run_it_would_have_consumed_once() throws Exception {
        recordRun(TriggerRunLog.currentNodeId());
        crashPrepared(RUN_ID);
        final var startupRuns = TriggerRunRecovery.startupRunIds();

        TriggerRunRecovery.recoverLocal(startupRuns);
        TwoPhaseParticipant.abortFromDurable(txId, 0L);

        assertEquals(List.of(RUN_ID), queuedRunIds(),
                "the aborted slice was the run's only submitter, so the abort must hand it back exactly once");
        assertFalse(Tx2pcLog.isPrepared(txId));
    }

    @Test
    public void test_an_aborted_slice_does_not_requeue_a_run_that_is_no_longer_pending() throws Exception {
        recordRun(TriggerRunLog.currentNodeId());
        crashPrepared(RUN_ID);
        TriggerDispatcher.consumeQuietly(RUN_ID, "audit");

        TwoPhaseParticipant.abortFromDurable(txId, 0L);

        assertTrue(queuedRunIds().isEmpty());
    }

    @Test
    public void test_an_aborted_slice_does_not_requeue_another_nodes_run() throws Exception {
        recordRun("another-node");
        crashPrepared(RUN_ID);

        TwoPhaseParticipant.abortFromDurable(txId, 0L);

        assertTrue(queuedRunIds().isEmpty());
        TriggerDispatcher.consumeQuietly(RUN_ID, "audit");
    }

    @Test
    public void test_requeue_runs_does_nothing_for_no_runs_or_disabled_triggers() throws Exception {
        recordRun(TriggerRunLog.currentNodeId());

        TriggerRunRecovery.requeueRuns(Set.of());
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TriggerRunRecovery.requeueRuns(Set.of(RUN_ID));
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);

        assertTrue(queuedRunIds().isEmpty());
    }

    @Test
    public void test_fenced_slices_name_only_the_runs_they_consume() throws Exception {
        crashPrepared(RUN_ID);

        assertEquals(Set.of(RUN_ID), FencedTriggerRuns.consumedBySlice(txId));
        assertTrue(FencedTriggerRuns.consumedByFencedSlices().contains(RUN_ID));
    }

    @Test
    public void test_only_a_trigger_run_consume_op_names_a_run() {
        final var consume = new JsonObject();
        consume.add(AdminTransactionEntry.TRIGGER_RUN_ID_FIELD, new JsonString(RUN_ID));

        assertEquals(RUN_ID, new AdminTransactionEntry("tx", "c", 1L, AdminTransactionEntry.OP_TYPE_DELETE_TRIGGER_RUN,
                "", "", consume).consumedTriggerRunId());
        assertNull(new AdminTransactionEntry("tx", "c", 1L, AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB,
                TestGlobals.COLL, consume).consumedTriggerRunId());
        assertNull(new AdminTransactionEntry("tx", "c", 1L, AdminTransactionEntry.OP_TYPE_DELETE_TRIGGER_RUN, "", "",
                new JsonObject()).consumedTriggerRunId());
    }
}
