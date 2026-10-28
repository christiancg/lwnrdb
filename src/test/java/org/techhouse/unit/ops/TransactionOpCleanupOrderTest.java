package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Transaction;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TransactionRecordCalls;
import org.techhouse.test.TriggerTestSupport;

public class TransactionOpCleanupOrderTest {
    private static final String TRIGGER = "cleanup-order";
    private static final String TRANSIENT_ID = "transient";
    private static final String COORDINATOR = "127.0.0.1:9999";
    private static final Configuration configuration = Configuration.getInstance();

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TriggerTestSupport.consumeAllPendingRuns();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void triggerOn(EventType type, String mode) {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(new TriggerDefinition(TRIGGER,
                new LinkedHashSet<>(Set.of(type)), "recalc", mode, false, true, "owner", 1L, 1L, 1L, "owner")));
    }

    private UUID openTransaction() {
        final var clientId = clientTracker.registerForwardedClient("cleanup-" + UUID.randomUUID());
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
        return clientId;
    }

    private Transaction transactionOf(UUID clientId) {
        return clientTracker.getActiveTransaction(clientId);
    }

    private void save(UUID clientId, String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        assertEquals(OperationStatus.OK,
                TransactionOperationHelper.bufferSave(request, transactionOf(clientId)).getStatus());
    }

    private void deleteTransientDocument(UUID clientId) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(TRANSIENT_ID);
        assertEquals(OperationStatus.OK,
                TransactionOperationHelper.bufferDelete(request, transactionOf(clientId)).getStatus());
    }

    private boolean anyOpLeft(List<String> opIds) {
        return opIds.stream().anyMatch(id -> cache.getTransactionPkIndexes().containsKey(id));
    }

    private boolean everyOpLeft(List<String> opIds) {
        return opIds.stream().allMatch(id -> cache.getTransactionPkIndexes().containsKey(id));
    }

    private boolean isVisible(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request) instanceof FindByIdResponse found && found.getObject() != null;
    }

    private static Answer<Object> failingTheFirstFinish(String txId, AtomicInteger failures) {
        return invocation -> {
            if (TransactionRecordCalls.clearsLocalCommit(invocation, txId) && failures.getAndDecrement() > 0) {
                throw new IOException("killed before the finish record landed");
            }
            return invocation.callRealMethod();
        };
    }

    @Test
    public void test_commit_clears_marker_and_ops_in_one_record() {
        final var clientId = openTransaction();
        save(clientId, "order-a");
        save(clientId, "order-b");
        final var txId = transactionOf(clientId).getTransactionId().toString();
        final var opIds = List.copyOf(transactionOf(clientId).getBufferedOpIds());
        final var finishes = new CopyOnWriteArrayList<List<String>>();
        final var opDeletions = new AtomicInteger();

        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if (TransactionRecordCalls.clearsLocalCommit(invocation, txId)) {
                finishes.add(List.copyOf(TransactionRecordCalls.deletesOf(invocation)));
            } else if (TransactionRecordCalls.isWrite(invocation)
                    && TransactionRecordCalls.deletesOf(invocation).stream().anyMatch(opIds::contains)) {
                opDeletions.incrementAndGet();
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        }

        assertEquals(1, finishes.size(), "the marker is cleared by exactly one record: " + finishes);
        assertTrue(finishes.getFirst().containsAll(opIds), "that record deletes every op: " + finishes);
        assertEquals(0, opDeletions.get(), "no op is deleted by a record of its own");
        assertFalse(TxCommitLog.isLocallyCommitted(txId));
        assertFalse(anyOpLeft(opIds));
    }

    @Test
    public void test_a_failed_finish_record_fences_and_startup_finishes_it() throws Exception {
        triggerOn(EventType.CREATED, TriggerDefinition.MODE_BATCH);
        final var clientId = openTransaction();
        save(clientId, "batch-a");
        save(clientId, "batch-b");
        save(clientId, "batch-c");
        final var txId = transactionOf(clientId).getTransactionId().toString();
        final var opIds = List.copyOf(transactionOf(clientId).getBufferedOpIds());
        final var failures = new AtomicInteger(1);

        try (var ignored = mockStatic(AdminOperationHelper.class, failingTheFirstFinish(txId, failures))) {
            assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(),
                    TransactionOperationHelper.commit(clientId).getErrorCode());
        }

        assertTrue(TxCommitLog.isLocallyCommitted(txId), "nothing of the finish record landed");
        assertTrue(everyOpLeft(opIds), "a replay needs every op while the marker stands");
        assertTrue(locks.holdsCollectionLock(TestGlobals.DB, TestGlobals.COLL), "the fence keeps its locks");
        TestUtils.releaseAllLocks();
        clientTracker.removeById(clientId);

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertFalse(TxCommitLog.isLocallyCommitted(txId));
        assertFalse(anyOpLeft(opIds), "the replay's own finish record removed the ops");
        assertFalse(locks.holdsCollectionLock(TestGlobals.DB, TestGlobals.COLL));
        final var runs = TriggerRunLog.pending();
        assertEquals(1, runs.size());
        assertEquals(Set.of("batch-a", "batch-b", "batch-c"), Set.copyOf(runs.getFirst().getIds()),
                "the replay re-staged the whole batch run");
        assertTrue(isVisible("batch-a") && isVisible("batch-b") && isVisible("batch-c"));
    }

    @Test
    public void test_a_replayed_created_then_deleted_id_fires_no_deleted() throws Exception {
        triggerOn(EventType.DELETED, TriggerDefinition.MODE_DOCUMENT);
        final var clientId = openTransaction();
        save(clientId, TRANSIENT_ID);
        deleteTransientDocument(clientId);
        final var txId = transactionOf(clientId).getTransactionId().toString();
        final var failures = new AtomicInteger(1);

        try (var ignored = mockStatic(AdminOperationHelper.class, failingTheFirstFinish(txId, failures))) {
            assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(),
                    TransactionOperationHelper.commit(clientId).getErrorCode());
        }
        TestUtils.releaseAllLocks();
        clientTracker.removeById(clientId);
        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertTrue(TriggerRunLog.pending().isEmpty(), "no reader outside the transaction ever saw that document");
        assertFalse(isVisible(TRANSIENT_ID));
    }

    private UUID preparedParticipantSaving(String... ids) {
        final var clientId = clientTracker.registerForwardedClient("participant-" + UUID.randomUUID());
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
        for (final var id : ids) {
            save(clientId, id);
        }
        assertTrue(TwoPhaseParticipant.prepare(clientId, COORDINATOR, List.of(COORDINATOR)));
        return clientId;
    }

    private void forget(UUID clientId, String dtxId, List<String> opIds) throws Exception {
        AdminOperationHelper.writeTransactionRecords(List.of(), opIds);
        AdminOperationHelper
                .deleteTransactionOps(List.of(Tx2pcLog.participantMarkerId(dtxId), TxCommitLog.markerId(dtxId)));
        TestUtils.releaseAllLocks();
        clientTracker.clearActiveTransaction(clientId);
        clientTracker.clearTransactionState(clientId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_commit_prepared_resolves_markers_and_ops_in_one_record() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        final var clientId = preparedParticipantSaving("p-a", "p-b");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var dtxId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        final var writes = new CopyOnWriteArrayList<InvocationOnMock>();

        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if (TransactionRecordCalls.isWrite(invocation)) {
                writes.add(invocation);
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(OperationStatus.OK, TwoPhaseParticipant.commitPrepared(clientId).getStatus());
        }

        assertEquals(2, writes.size(), "a decided participant writes its begin and its finish record");
        final var begin = writes.getFirst();
        assertTrue(TransactionRecordCalls.writesLocalCommit(begin, dtxId));
        assertTrue(TransactionRecordCalls.deletes(begin, Tx2pcLog.participantMarkerId(dtxId)));
        final var finish = writes.getLast();
        assertTrue(TransactionRecordCalls.clearsLocalCommit(finish, dtxId));
        assertTrue(TransactionRecordCalls.deletesOf(finish).containsAll(opIds));
        assertTrue(TransactionRecordCalls.inserts(finish, dtxId + Globals.COLL_IDENTIFIER_SEPARATOR + "outcome"),
                "the outcome rides the finish record");
        assertFalse(Tx2pcLog.isPrepared(dtxId));
        assertFalse(anyOpLeft(opIds));
        assertNotNull(Tx2pcLog.readOutcome(dtxId));
        Tx2pcLog.deleteOutcomeMarker(dtxId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_a_failed_begin_record_leaves_the_slice_prepared() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        final var clientId = preparedParticipantSaving("p-fenced-a", "p-fenced-b");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var dtxId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());

        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if (TransactionRecordCalls.writesLocalCommit(invocation, dtxId)) {
                throw new IOException("marker unresolvable");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(),
                    TwoPhaseParticipant.commitPrepared(clientId).getErrorCode());
        }

        assertTrue(Tx2pcLog.isPrepared(dtxId));
        assertFalse(TxCommitLog.isLocallyCommitted(dtxId));
        assertTrue(everyOpLeft(opIds), "recovery replays the slice the PREPARED marker points at");
        forget(clientId, dtxId, opIds);
    }

    @Test
    public void test_a_failed_finish_after_resolution_keeps_the_slice_committing() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        final var clientId = preparedParticipantSaving("p-committing");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var dtxId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        final var failures = new AtomicInteger(1);

        try (var ignored = mockStatic(AdminOperationHelper.class, failingTheFirstFinish(dtxId, failures))) {
            assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(),
                    TwoPhaseParticipant.commitPrepared(clientId).getErrorCode());
        }

        assertTrue(TxCommitLog.isLocallyCommitted(dtxId));
        assertFalse(Tx2pcLog.isPrepared(dtxId), "the begin record replaced the prepared marker");
        assertNull(Tx2pcLog.readOutcome(dtxId), "the outcome rides the finish record that never landed");
        assertEquals(Tx2pcLog.Status.COMMITTED, Tx2pcLog.status(dtxId, null));
        assertTrue(everyOpLeft(opIds));
        forget(clientId, dtxId, opIds);
    }
}
