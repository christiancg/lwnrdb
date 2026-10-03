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
import org.techhouse.ops.TxCommitLog;
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

    private String txIdOf(UUID clientId) {
        return clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
    }

    private boolean sliceSurvives(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        return transaction != null && !transaction.getBufferedOpIds().isEmpty()
                && transaction.getBufferedOpIds().stream().allMatch(id -> cache.getPkIndexTransaction(id) != null);
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
}
