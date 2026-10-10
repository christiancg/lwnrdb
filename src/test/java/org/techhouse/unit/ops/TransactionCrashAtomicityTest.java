package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.DurableReplayIncompleteException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.TransactionRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionCrashAtomicityTest {
    private final Cache cache = IocContainer.get(Cache.class);

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

    // Buffers a slice without committing it, exactly as a client transaction would have on disk when the
    // process died: the ops are durable and the marker says the commit had been decided.
    private Transaction bufferSlice(String... ids) {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        for (final var id : ids) {
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObject(document(id));
            request.set_id(id);
            TransactionOperationHelper.bufferSave(request, transaction);
        }
        return transaction;
    }

    private boolean documentExists(String id) throws Exception {
        return !cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, java.util.Set.of(id)).isEmpty();
    }

    @Test
    public void test_an_unfinishable_commit_fences_its_collections() throws Exception {
        final var clientTracker = IocContainer.get(org.techhouse.conn.ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient("fencer");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("fenced"));
        request.set_id("fenced");
        TransactionOperationHelper.bufferSave(request, transaction);
        final var corrupt = new org.techhouse.data.admin.AdminTransactionEntry(
                transaction.getTransactionId().toString(), "client", 99,
                org.techhouse.data.admin.AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL,
                new JsonObject());
        AdminOperationHelper.saveTransactionOp(corrupt);
        transaction.getBufferedOpIds().add(corrupt.get_id());

        final var response = TransactionOperationHelper.commit(clientId);

        assertEquals("500-33", response.getErrorCode(),
                "an unfinishable commit must surface its own code, not a generic transaction error");
        assertNotNull(clientTracker.getActiveTransaction(clientId),
                "the transaction stays registered so its collections stay fenced rather than serving a half-state");
        assertTrue(TxCommitLog.isLocallyCommitted(transaction.getTransactionId().toString()),
                "the commit log must survive so restart recovery can finish the slice");

        TransactionOperationHelper.abortInPlace(clientId);
        assertTrue(TxCommitLog.isLocallyCommitted(transaction.getTransactionId().toString()),
                "teardown must not discard the slice the fence is holding for recovery");

        TxCommitLog.clearLocalCommit(transaction.getTransactionId().toString());
        TransactionOperationHelper.abortInPlace(clientId);
    }

    @Test
    public void test_partially_applied_commit_is_finished_at_startup() throws Exception {
        final var transaction = bufferSlice("a", "b", "c");
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        // Simulates the crash: the first op landed, the rest did not.
        assertTrue(TransactionRecovery.finishLocalCommit(txId));

        assertTrue(documentExists("a"));
        assertTrue(documentExists("b"));
        assertTrue(documentExists("c"));
        assertFalse(TxCommitLog.isLocallyCommitted(txId));
    }

    @Test
    public void test_startup_sweep_finishes_a_marked_commit_and_discards_an_unmarked_one() throws Exception {
        final var decided = bufferSlice("decided");
        TxCommitLog.recordLocalCommit(decided.getTransactionId().toString(), decided.getBufferedOpIds(),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        final var undecided = bufferSlice("undecided");
        TestUtils.releaseAllLocks();

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertTrue(documentExists("decided"), "a decided commit must be finished, not discarded");
        assertFalse(documentExists("undecided"), "a transaction still buffering must be discarded as before");
        assertTrue(cache.getTransactionPkIndexes().isEmpty(), "no slice or marker may survive the sweep");
        assertNotNull(undecided);
    }

    @Test
    public void test_replay_is_idempotent_for_already_applied_ops() throws Exception {
        final var transaction = bufferSlice("dup");
        final var txId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        TxCommitLog.recordLocalCommit(txId, opIds,
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        final var ops = AdminOperationHelper.readTransactionOps(opIds);
        assertEquals(1, ops.size());

        assertTrue(TransactionRecovery.finishLocalCommit(txId));
        assertTrue(TransactionRecovery.finishLocalCommit(txId));

        assertEquals(1, cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, java.util.Set.of("dup")).size());
    }

    @Test
    public void test_a_partially_applied_startup_replay_keeps_its_collection_locked() throws Exception {
        final var transaction = bufferSlice("stuck-at-startup");
        final var txId = transaction.getTransactionId().toString();
        final var corrupt = new AdminTransactionEntry(txId, "client", 99, AdminTransactionEntry.OP_TYPE_SAVE,
                TestGlobals.DB, "ghost-collection", document("unreachable"));
        AdminOperationHelper.saveTransactionOp(corrupt);
        transaction.getBufferedOpIds().add(corrupt.get_id());
        final var collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collId));
        TestUtils.releaseAllLocks();

        assertFalse(TransactionRecovery.finishLocalCommit(txId));

        final var locks = IocContainer.get(ResourceLocking.class);
        try {
            assertTrue(documentExists("stuck-at-startup"), "the op that landed before the corrupt one stays applied");
            assertTrue(locks.isWriteLockedByCurrentThread(collId),
                    "a replay that could not finish applying must keep its collection locked, not release it");
            assertTrue(TxCommitLog.isLocallyCommitted(txId),
                    "the local-commit marker must survive so a later attempt can retry the slice");
            assertEquals(2, AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds()).size(),
                    "the op slice must not be discarded while the replay is stuck");
        } finally {
            locks.releaseWrite(collId);
            TxCommitLog.clearLocalCommit(txId);
        }
    }

    @Test
    public void test_a_partially_applied_2pc_recovery_replay_keeps_its_collection_locked() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        final var collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        final var goodOp = new AdminTransactionEntry(dtxId, "client", 0, AdminTransactionEntry.OP_TYPE_SAVE,
                TestGlobals.DB, TestGlobals.COLL, document("prepared-doc"));
        AdminOperationHelper.saveTransactionOp(goodOp);
        final var corrupt = new AdminTransactionEntry(dtxId, "client", 1, AdminTransactionEntry.OP_TYPE_SAVE,
                TestGlobals.DB, "ghost-collection", document("unreachable"));
        AdminOperationHelper.saveTransactionOp(corrupt);
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"), List.of(collId));

        assertThrows(DurableReplayIncompleteException.class,
                () -> TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 0L));

        final var locks = IocContainer.get(ResourceLocking.class);
        try {
            assertTrue(documentExists("prepared-doc"), "the op that landed before the corrupt one stays applied");
            assertTrue(locks.isWriteLockedByCurrentThread(collId),
                    "a 2PC recovery replay that could not finish applying must keep its collection locked");
            assertTrue(TxCommitLog.isLocallyCommitted(dtxId),
                    "the decided marker must survive so the next recovery round can retry the slice");
        } finally {
            locks.releaseWrite(collId);
            Tx2pcLog.deleteParticipantMarker(dtxId);
            AdminOperationHelper.deleteTransactionOps(List.of(goodOp.get_id(), corrupt.get_id()));
        }
    }

    @Test
    public void test_a_fully_applied_startup_replay_still_releases_its_lock() throws Exception {
        final var transaction = bufferSlice("released-at-startup");
        final var txId = transaction.getTransactionId().toString();
        final var collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collId));
        TestUtils.releaseAllLocks();

        assertTrue(TransactionRecovery.finishLocalCommit(txId));

        final var locks = IocContainer.get(ResourceLocking.class);
        assertTrue(documentExists("released-at-startup"));
        assertFalse(locks.isWriteLockedByCurrentThread(collId),
                "a replay that finishes applying must still release its lock, not hold it forever");
        assertFalse(TxCommitLog.isLocallyCommitted(txId));
    }

    @Test
    public void test_local_commit_marker_round_trips() throws Exception {
        final var txId = UUID.randomUUID().toString();
        TxCommitLog.recordLocalCommit(txId, List.of("op1", "op2"), List.of("db|coll"));

        assertTrue(TxCommitLog.isLocallyCommitted(txId));
        assertTrue(TxCommitLog.localCommitTxIds().contains(txId));
        final var marker = TxCommitLog.readLocalCommitMarker(txId);
        assertNotNull(marker);
        assertEquals(List.of("op1", "op2"), marker.opIds());
        assertEquals(List.of("db|coll"), marker.collections());

        TxCommitLog.clearLocalCommit(txId);
        assertFalse(TxCommitLog.isLocallyCommitted(txId));
    }

    @Test
    public void test_reading_a_missing_marker_returns_null() throws Exception {
        org.junit.jupiter.api.Assertions.assertNull(TxCommitLog.readLocalCommitMarker(UUID.randomUUID().toString()));
    }
}
