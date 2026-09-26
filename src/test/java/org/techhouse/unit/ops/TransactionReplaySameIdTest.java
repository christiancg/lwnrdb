package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
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
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionReplaySameIdTest {
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

    private static JsonObject document(String id, String value) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("status", new JsonString(value));
        return object;
    }

    private static String collectionId() {
        return Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    }

    private void bufferSave(Transaction transaction, String id, String value) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, value));
        request.set_id(id);
        TransactionOperationHelper.bufferSave(request, transaction);
    }

    private String storedStatus(String id) throws Exception {
        final var entries = cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of(id));
        return entries.isEmpty() ? null : entries.getFirst().getData().get("status").asJsonString().getValue();
    }

    @Test
    public void test_two_saves_on_one_id_both_replay_at_startup() throws Exception {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        bufferSave(transaction, "o1", "new");
        bufferSave(transaction, "o1", "paid");
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collectionId()));
        TestUtils.releaseAllLocks();

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertEquals("paid", storedStatus("o1"),
                "the transaction's own first apply stamps a version above the commit marker, so a per-op fence"
                        + " made every later op on that id look like a foreign write and silently dropped it");
    }

    @Test
    public void test_a_save_then_delete_on_one_id_replays_the_delete() throws Exception {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        bufferSave(transaction, "o2", "new");
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("o2");
        TransactionOperationHelper.bufferDelete(delete, transaction);
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collectionId()));
        TestUtils.releaseAllLocks();

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertFalse(
                cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of("o2")).isEmpty()
                        && storedStatus("o2") != null,
                "a committed delete must not survive the replay as a live document");
    }

    @Test
    public void test_distinct_ids_still_all_replay() throws Exception {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        bufferSave(transaction, "d1", "a");
        bufferSave(transaction, "d2", "b");
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collectionId()));
        TestUtils.releaseAllLocks();

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertEquals("a", storedStatus("d1"));
        assertEquals("b", storedStatus("d2"));
        assertTrue(cache.getTransactionPkIndexes().isEmpty(), "no slice or marker may survive the sweep");
    }
}
