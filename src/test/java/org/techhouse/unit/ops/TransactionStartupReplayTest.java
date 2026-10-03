package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.TransactionRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionStartupReplayTest {
    private static final String LATE_COLL = "late-coll";

    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final HybridClock clock = IocContainer.get(HybridClock.class);
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
    }

    @AfterEach
    void releaseLocks() throws Exception {
        TestUtils.setPrivateField(org.techhouse.config.Configuration.getInstance(), "clusterEnabled", false);
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

    @Test
    public void test_startup_replay_finishes_two_saves_of_one_id_after_the_first_applied() throws Exception {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        TransactionOperationHelper.bufferSave(saveRequest(document("twice", "first")), transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("twice", "second")), transaction);
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collId));
        SaveOperationHelper.executeSave(saveRequest(document("twice", "first")));
        TestUtils.releaseAllLocks();

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertEquals("second", valueOf("twice"),
                "the transaction's own applied prefix must not fence the rest of its slice at startup");
        assertFalse(TxCommitLog.isLocallyCommitted(txId));
    }

    @Test
    public void test_startup_replay_finishes_a_save_then_delete_of_one_id() throws Exception {
        applyDirectly(document("doomed", "original"));
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        TransactionOperationHelper.bufferSave(saveRequest(document("doomed", "updated")), transaction);
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("doomed");
        TransactionOperationHelper.bufferDelete(delete, transaction);
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collId));
        SaveOperationHelper.executeSave(saveRequest(document("doomed", "updated")));
        TestUtils.releaseAllLocks();

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertNull(valueOf("doomed"), "the delete after the applied save must still run at startup");
    }

    private String prepareSlice(JsonObject... documents) throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        for (var seq = 0; seq < documents.length; seq++) {
            AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", seq,
                    AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, documents[seq]));
        }
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"), List.of(collId));
        return dtxId;
    }

    @Test
    public void test_a_clustered_startup_replay_still_fences_what_the_commit_already_wrote() throws Exception {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        TransactionOperationHelper.bufferSave(saveRequest(document("fenced", "first")), transaction);
        TransactionOperationHelper.bufferSave(saveRequest(document("fenced", "second")), transaction);
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collId));
        SaveOperationHelper.executeSave(saveRequest(document("fenced", "first")));
        TestUtils.releaseAllLocks();
        TestUtils.setPrivateField(org.techhouse.config.Configuration.getInstance(), "clusterEnabled", true);

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertEquals("first", valueOf("fenced"),
                "clustered, the new owner may have written newer values while this node was down, and a replay"
                        + " at a fresh version would overwrite them cluster-wide");
    }

    @Test
    public void test_prepared_replay_still_fences_a_write_made_after_prepare() throws Exception {
        final var dtxId = prepareSlice(document("foreign", "mine"));
        applyDirectly(document("foreign", "theirs"));

        TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 0L);

        assertEquals("theirs", valueOf("foreign"), "a write made after prepare is a foreign write");
    }

    @Test
    public void test_a_failed_then_successful_replay_leaves_no_write_lock_held() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", 0,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, document("retried", "v")));
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", 1,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, LATE_COLL, document("late", "v")));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"), List.of(collId));

        TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 1000L);
        assertTrue(locks.isWriteLockedByCurrentThread(collId), "the failed attempt keeps its lock by design");

        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, LATE_COLL));
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, LATE_COLL);
        TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 1000L);

        assertFalse(Tx2pcLog.isPrepared(dtxId));
        assertFalse(locks.isWriteLockedByCurrentThread(collId),
                "a retry on the same thread must not leave the earlier attempt's hold behind");
        final var otherThread = new boolean[1];
        final var probe = Thread.ofPlatform().start(() -> {
            otherThread[0] = locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL);
            if (otherThread[0]) {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        });
        probe.join();
        assertTrue(otherThread[0], "another writer must be able to take the collection after the retry");
    }
}
