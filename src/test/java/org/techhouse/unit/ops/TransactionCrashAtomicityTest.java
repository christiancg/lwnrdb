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
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * A single-node commit records a durable intent marker before it applies anything, so a crash mid-commit is
 * finished at startup rather than leaving the transaction half-applied and discarding the rest.
 */
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
    public void test_partially_applied_commit_is_finished_at_startup() throws Exception {
        final var transaction = bufferSlice("a", "b", "c");
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        // Simulates the crash: the first op landed, the rest did not.
        TransactionOperationHelper.commitLocalFromDurable(txId,
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));

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

    // Replay rewrites the ops a crash already applied. They carry whole values, so the state converges rather
    // than compounding - which is what makes replaying a partial commit safe.
    @Test
    public void test_replay_is_idempotent_for_already_applied_ops() throws Exception {
        final var transaction = bufferSlice("dup");
        final var txId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        TxCommitLog.recordLocalCommit(txId, opIds,
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        final var ops = AdminOperationHelper.readTransactionOps(opIds);
        assertEquals(1, ops.size());

        TransactionOperationHelper.commitLocalFromDurable(txId,
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        // A second pass finds no slice left and must be a harmless no-op.
        TransactionOperationHelper.commitLocalFromDurable(txId,
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));

        assertEquals(1, cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, java.util.Set.of("dup")).size());
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
