package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionFencedTeardownTest {

    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void freshCollection() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private UUID fencedTransaction(String id) throws Exception {
        final var clientId = clientTracker.registerForwardedClient("fencer");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        TransactionOperationHelper.bufferSave(request, transaction);
        TxCommitLog.recordLocalCommit(transaction.getTransactionId().toString(), transaction.getBufferedOpIds(),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        return clientId;
    }

    private UUID preparedTransaction(String id) throws Exception {
        final var clientId = clientTracker.registerForwardedClient("preparer");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        TransactionOperationHelper.bufferSave(request, transaction);
        Tx2pcLog.recordParticipantPrepared(transaction.getTransactionId().toString(), "127.0.0.1:9000",
                List.of("127.0.0.1:9000"), List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        return clientId;
    }

    private String txIdOf(UUID clientId) {
        return clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
    }

    private boolean sliceSurvives(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        return transaction != null && !transaction.getBufferedOpIds().isEmpty()
                && transaction.getBufferedOpIds().stream().allMatch(id -> cache.getPkIndexTransaction(id) != null);
    }

    private void assertEveryWriteIsRefusedAsHalfApplied(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(document("late"));
        save.set_id("late");
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document("late_bulk")));
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("late");

        assertEquals("500-33", TransactionOperationHelper.bufferSave(save, transaction).getErrorCode());
        assertEquals("500-33", TransactionOperationHelper.bufferBulkSave(bulk, transaction).getErrorCode());
        assertEquals("500-33", TransactionOperationHelper.bufferDelete(delete, transaction).getErrorCode());
        assertEquals(opIds, transaction.getBufferedOpIds(),
                "an op buffered after the commit point would be replayed although the client never committed it");
    }

    private void discardFence(UUID clientId) throws Exception {
        TxCommitLog.clearLocalCommit(txIdOf(clientId));
        TransactionOperationHelper.abortInPlace(clientId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_rollback_refuses_to_discard_a_fenced_slice() throws Exception {
        final var clientId = fencedTransaction("rolled");
        final var txId = txIdOf(clientId);

        final var response = TransactionOperationHelper.rollback(clientId);

        assertEquals("500-33", response.getErrorCode(),
                "rolling back a fenced commit must report it as half applied, not as a clean rollback");
        assertTrue(TxCommitLog.isLocallyCommitted(txId), "the commit marker must survive the rollback attempt");
        assertTrue(sliceSurvives(clientId), "the slice the marker points at must survive the rollback attempt");
        assertNotNull(clientTracker.getActiveTransaction(clientId),
                "the transaction stays registered so recovery can still name its locks");
        discardFence(clientId);
    }

    @Test
    public void test_rollback_refuses_to_discard_a_2pc_prepared_slice() throws Exception {
        final var clientId = preparedTransaction("prepared");
        final var txId = txIdOf(clientId);

        final var response = TransactionOperationHelper.rollback(clientId);

        assertEquals("500-33", response.getErrorCode(),
                "a client rollback after an indeterminate 2PC commit must report the slice as half applied");
        assertTrue(Tx2pcLog.isPrepared(txId), "the prepared marker must survive the rollback attempt");
        assertTrue(sliceSurvives(clientId), "the slice recovery will re-drive must survive the rollback attempt");
        assertNotNull(clientTracker.getActiveTransaction(clientId),
                "the transaction stays registered so its write locks still have an owner");
        Tx2pcLog.deleteParticipantMarker(txId);
        TransactionOperationHelper.abortInPlace(clientId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_abort_still_discards_a_prepared_but_undecided_slice() throws Exception {
        final var clientId = preparedTransaction("undecided");
        final var txId = txIdOf(clientId);
        final var opIds = List.copyOf(clientTracker.getActiveTransaction(clientId).getBufferedOpIds());

        final var response = TransactionOperationHelper.abort(clientId);

        assertEquals(OperationStatus.OK, response.getStatus(),
                "the coordinator's abort of a prepared slice it has not decided must still succeed");
        assertFalse(Tx2pcLog.isPrepared(txId), "the abort must resolve the prepared marker");
        assertFalse(opIds.stream().anyMatch(id -> cache.getPkIndexTransaction(id) != null),
                "the abort must discard the prepared slice's ops");
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_abort_in_place_refuses_to_discard_a_fenced_slice() throws Exception {
        final var clientId = fencedTransaction("aborted");
        final var txId = txIdOf(clientId);

        TransactionOperationHelper.abortInPlace(clientId);

        assertTrue(TxCommitLog.isLocallyCommitted(txId), "the commit marker must survive an in-place abort");
        assertTrue(sliceSurvives(clientId), "the slice the marker points at must survive an in-place abort");
        discardFence(clientId);
    }

    @Test
    public void test_abort_refuses_to_discard_a_fenced_slice() throws Exception {
        final var clientId = fencedTransaction("discarded");
        final var txId = txIdOf(clientId);

        final var response = TransactionOperationHelper.abort(clientId);

        assertEquals("500-33", response.getErrorCode(), "aborting a fenced commit must report it as half applied");
        assertTrue(TxCommitLog.isLocallyCommitted(txId), "the commit marker must survive the abort attempt");
        assertTrue(sliceSurvives(clientId), "the slice the marker points at must survive the abort attempt");
        discardFence(clientId);
    }

    @Test
    public void test_disconnect_cleanup_refuses_to_discard_a_fenced_slice() throws Exception {
        final var clientId = fencedTransaction("dropped");
        final var txId = txIdOf(clientId);

        TransactionOperationHelper.cleanupOnDisconnect(clientId);

        assertTrue(TxCommitLog.isLocallyCommitted(txId),
                "a client vanishing must not destroy the slice recovery will finish");
        assertTrue(sliceSurvives(clientId), "the slice must outlive the connection that created it");
        discardFence(clientId);
    }

    @Test
    public void test_shutdown_leaves_a_fenced_transaction_for_recovery() throws Exception {
        final var clientId = fencedTransaction("shutdown");
        final var txId = txIdOf(clientId);

        TransactionOperationHelper.rollbackOpenTransactionsAtShutdown();

        assertTrue(TxCommitLog.isLocallyCommitted(txId), "shutdown must leave a fenced commit for restart recovery");
        assertTrue(sliceSurvives(clientId), "shutdown must not discard the ops the marker points at");
        discardFence(clientId);
    }

    @Test
    public void test_an_unfenced_transaction_is_still_rolled_back_normally() {
        final var clientId = clientTracker.registerForwardedClient("ordinary");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("ordinary"));
        request.set_id("ordinary");
        TransactionOperationHelper.bufferSave(request, transaction);
        final var opIds = List.copyOf(transaction.getBufferedOpIds());

        final var response = TransactionOperationHelper.rollback(clientId);

        assertEquals(OperationStatus.OK, response.getStatus(),
                "a transaction with no commit marker must still roll back cleanly");
        assertFalse(opIds.stream().anyMatch(id -> cache.getPkIndexTransaction(id) != null),
                "an ordinary rollback must still discard its buffered ops");
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_a_write_into_a_locally_fenced_transaction_answers_500_33_and_buffers_nothing() throws Exception {
        final var clientId = fencedTransaction("fenced_writes");

        assertEveryWriteIsRefusedAsHalfApplied(clientId);

        discardFence(clientId);
    }

    @Test
    public void test_a_write_into_a_prepared_2pc_slice_answers_500_33() throws Exception {
        final var clientId = preparedTransaction("prepared_writes");

        assertEveryWriteIsRefusedAsHalfApplied(clientId);

        Tx2pcLog.deleteParticipantMarker(txIdOf(clientId));
        TransactionOperationHelper.abortInPlace(clientId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_abort_in_place_keeps_a_prepared_slice() throws Exception {
        final var clientId = preparedTransaction("prepared_kept");
        final var txId = txIdOf(clientId);

        TransactionOperationHelper.abortInPlace(clientId);

        assertTrue(Tx2pcLog.isPrepared(txId), "the prepared marker must survive an in-place abort");
        assertTrue(sliceSurvives(clientId), "a decided 2PC slice must not be discarded by a lock timeout");
        assertFalse(clientTracker.getActiveTransaction(clientId).isAborted());
        Tx2pcLog.deleteParticipantMarker(txId);
        TransactionOperationHelper.abortInPlace(clientId);
        clientTracker.removeById(clientId);
    }
}
