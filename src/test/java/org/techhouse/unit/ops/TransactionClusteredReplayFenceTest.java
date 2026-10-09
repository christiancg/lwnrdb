package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.HybridClock;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.TransactionRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionClusteredReplayFenceTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final HybridClock clock = IocContainer.get(HybridClock.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final String collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);

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
        clock.observe(clock.next());
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterEnabled", true);
    }

    @AfterEach
    void releaseLocks() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterEnabled", false);
        TestUtils.releaseAllLocks();
    }

    private static JsonObject document(String id, String value) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString(value));
        return object;
    }

    private static SaveRequest saveRequest(JsonObject document) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document);
        request.set_id(document.get(Globals.PK_FIELD).asJsonString().getValue());
        return request;
    }

    private void applyDirectly(JsonObject document) throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeSave(saveRequest(document));
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private String valueOf(String id) throws Exception {
        final var found = cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of(id));
        return found.isEmpty() ? null : found.getFirst().getData().get("value").asJsonString().getValue();
    }

    private String commitMarkerFor(Transaction transaction) throws Exception {
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collId));
        return txId;
    }

    private Transaction newTransaction() {
        return new Transaction(UUID.randomUUID(), UUID.randomUUID());
    }

    private void crashAndRestart() throws Exception {
        TestUtils.releaseAllLocks();
        TransactionOperationHelper.cleanupOrphansAtStartup();
    }

    @Test
    public void test_finishes_two_saves_of_one_id_after_the_first_applied() throws Exception {
        final var transaction = newTransaction();
        TransactionOperationHelper.bufferSave(saveRequest(document("twice", "first")), transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("twice", "second")), transaction);
        final var txId = commitMarkerFor(transaction);
        applyDirectly(document("twice", "first"));

        crashAndRestart();

        assertEquals("second", valueOf("twice"));
        assertFalse(TxCommitLog.isLocallyCommitted(txId));
    }

    @Test
    public void test_finishes_a_save_then_delete_of_one_id() throws Exception {
        applyDirectly(document("doomed", "original"));
        final var transaction = newTransaction();
        TransactionOperationHelper.bufferSave(saveRequest(document("doomed", "updated")), transaction);
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("doomed");
        TransactionOperationHelper.bufferDelete(delete, transaction);
        commitMarkerFor(transaction);
        applyDirectly(document("doomed", "updated"));

        crashAndRestart();

        assertNull(valueOf("doomed"));
    }

    @Test
    public void test_applies_only_what_is_missing_when_the_second_of_three_saves_landed() throws Exception {
        final var transaction = newTransaction();
        TransactionOperationHelper.bufferSave(saveRequest(document("thrice", "one")), transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("thrice", "two")), transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("thrice", "three")), transaction);
        commitMarkerFor(transaction);
        applyDirectly(document("thrice", "one"));
        applyDirectly(document("thrice", "two"));

        crashAndRestart();

        assertEquals("three", valueOf("thrice"));
    }

    @Test
    public void test_keeps_a_foreign_write_to_an_id_the_transaction_touched_twice() throws Exception {
        final var transaction = newTransaction();
        TransactionOperationHelper.bufferSave(saveRequest(document("contested", "one")), transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("contested", "two")), transaction);
        commitMarkerFor(transaction);
        applyDirectly(document("contested", "theirs"));

        crashAndRestart();

        assertEquals("theirs", valueOf("contested"));
    }

    @Test
    public void test_keeps_a_foreign_write_to_an_id_the_transaction_touched_once() throws Exception {
        final var transaction = newTransaction();
        TransactionOperationHelper.bufferSave(saveRequest(document("solo", "mine")), transaction);
        commitMarkerFor(transaction);
        applyDirectly(document("solo", "theirs"));

        crashAndRestart();

        assertEquals("theirs", valueOf("solo"));
    }

    @Test
    public void test_replays_every_op_when_the_crash_came_before_the_first_apply() throws Exception {
        final var transaction = newTransaction();
        TransactionOperationHelper.bufferSave(saveRequest(document("gone", "one")), transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("gone", "two")), transaction);
        commitMarkerFor(transaction);

        crashAndRestart();

        assertEquals("two", valueOf("gone"));
    }

    @Test
    public void test_finishes_a_bulk_save_then_a_save_of_an_id_inside_it() throws Exception {
        final var transaction = newTransaction();
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document("bulkA", "one"), document("bulkB", "one")));
        TransactionOperationHelper.bufferBulkSave(bulk, transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("bulkA", "two")), transaction);
        commitMarkerFor(transaction);
        applyDirectly(document("bulkA", "one"));
        applyDirectly(document("bulkB", "one"));

        crashAndRestart();

        assertEquals("two", valueOf("bulkA"));
        assertEquals("one", valueOf("bulkB"));
    }

    @Test
    public void test_prefers_the_last_matching_payload_when_an_id_returns_to_an_earlier_value() throws Exception {
        final var transaction = newTransaction();
        TransactionOperationHelper.bufferSave(saveRequest(document("flip", "a")), transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("flip", "b")), transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("flip", "a")), transaction);
        commitMarkerFor(transaction);
        applyDirectly(document("flip", "a"));
        applyDirectly(document("flip", "b"));

        crashAndRestart();

        assertEquals("a", valueOf("flip"));
    }

    @Test
    public void test_prepared_slice_replay_finishes_its_own_prefix() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        savePreparedOp(dtxId, 0, document("prep", "first"));
        savePreparedOp(dtxId, 1, document("prep", "second"));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"), List.of(collId));
        applyDirectly(document("prep", "first"));

        TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 0L);

        assertEquals("second", valueOf("prep"));
    }

    @Test
    public void test_prepared_slice_replay_keeps_a_foreign_write_to_a_multi_op_id() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        savePreparedOp(dtxId, 0, document("prepForeign", "first"));
        savePreparedOp(dtxId, 1, document("prepForeign", "second"));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"), List.of(collId));
        applyDirectly(document("prepForeign", "theirs"));

        TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 0L);

        assertEquals("theirs", valueOf("prepForeign"));
    }

    @Test
    public void test_prepared_slice_replay_does_not_recreate_an_id_a_client_deleted_after_the_restart()
            throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        savePreparedOp(dtxId, 0, document("deletedLater", "mine"));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"), List.of(collId));
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "deletedLater", clock.next());

        TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 0L);

        assertNull(valueOf("deletedLater"),
                "a delete accepted after the prepare is newer than the slice's save, as a newer save would be");
    }

    @Test
    public void test_prepared_slice_replay_recreates_an_id_deleted_before_the_prepare() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "deletedEarlier", clock.next());
        savePreparedOp(dtxId, 0, document("deletedEarlier", "mine"));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"), List.of(collId));

        TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 0L);

        assertEquals("mine", valueOf("deletedEarlier"));
    }

    @Test
    public void test_prepared_slice_replay_finishes_an_id_it_deleted_itself_before_saving() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        applyDirectly(document("ownDelete", "before"));
        final var delete = new JsonObject();
        delete.add(Globals.PK_FIELD, new JsonString("ownDelete"));
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", 0,
                AdminTransactionEntry.OP_TYPE_DELETE, TestGlobals.DB, TestGlobals.COLL, delete));
        savePreparedOp(dtxId, 1, document("ownDelete", "mine"));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"), List.of(collId));
        deleteOwnDeleteDirectly();
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "ownDelete", clock.next());

        TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 0L);

        assertEquals("mine", valueOf("ownDelete"),
                "the slice's own applied delete leaves a tombstone above the prepare too, so it cannot fence");
    }

    @Test
    public void test_prepared_slice_replay_fails_on_an_unreadable_tombstone_file() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        savePreparedOp(dtxId, 0, document("unreadableTombstones", "mine"));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"), List.of(collId));
        final var tombstones = new File(TestUtils.getDbPath(fs), TestGlobals.DB + File.separator + TestGlobals.COLL
                + File.separator + TestGlobals.COLL + "-tombstones.idx");
        Files.deleteIfExists(tombstones.toPath());
        assertTrue(tombstones.mkdirs(), "a directory in place of the tombstone file makes its read fail");
        try {
            assertThrows(Exception.class,
                    () -> TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 0L));

            assertNull(valueOf("unreadableTombstones"), "a fence that cannot be computed applies nothing");
            assertNotNull(Tx2pcLog.readParticipantMarker(dtxId), "the slice stays prepared for a retry");
        } finally {
            assertTrue(tombstones.delete());
        }
    }

    private void deleteOwnDeleteDirectly() throws Exception {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("ownDelete");
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            DeleteOperationHelper.executeDelete(request);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private void savePreparedOp(String dtxId, int seq, JsonObject document) throws Exception {
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", seq,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, document));
    }
}
