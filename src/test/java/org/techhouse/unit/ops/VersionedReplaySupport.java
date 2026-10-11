package org.techhouse.unit.ops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.VersionedApply;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

abstract class VersionedReplaySupport {
    protected static final String COORDINATOR = "127.0.0.1:5000";

    protected final Cache cache = IocContainer.get(Cache.class);
    protected final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    protected final HybridClock clock = IocContainer.get(HybridClock.class);
    protected final FileSystem fs = IocContainer.get(FileSystem.class);
    protected final String collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);

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
    void standaloneAgain() throws Exception {
        clustered(false);
        TestUtils.releaseAllLocks();
    }

    protected static void clustered(boolean enabled) throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterEnabled", enabled);
    }

    protected static JsonObject document(String id, String value) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString(value));
        return object;
    }

    protected static SaveRequest saveRequest(JsonObject document) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document);
        request.set_id(document.get(Globals.PK_FIELD).asJsonString().getValue());
        return request;
    }

    protected static Transaction newTransaction() {
        return new Transaction(UUID.randomUUID(), UUID.randomUUID());
    }

    protected static void bufferSave(Transaction transaction, String id, String value) {
        TransactionOperationHelper.bufferSave(saveRequest(document(id, value)), transaction);
    }

    protected static void bufferDelete(Transaction transaction, String id) {
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id(id);
        TransactionOperationHelper.bufferDelete(delete, transaction);
    }

    protected static void bufferBulkSave(Transaction transaction, JsonObject... documents) {
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(documents));
        TransactionOperationHelper.bufferBulkSave(bulk, transaction);
    }

    protected String commitMarkerFor(Transaction transaction) throws Exception {
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(collId));
        return txId;
    }

    protected void applyPrefix(List<String> opIds, int count) throws Exception {
        final var ops = new ArrayList<>(AdminOperationHelper.readTransactionOps(opIds));
        ops.sort(Comparator.comparingLong(AdminTransactionEntry::getSeq));
        final var applier = new VersionedApply();
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            for (final var op : ops.subList(0, count)) {
                applier.apply(op);
            }
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    protected void foreignWrite(String id, String value) throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeSave(saveRequest(document(id, value)));
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    protected String valueOf(String id) throws Exception {
        final var found = cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of(id));
        return found.isEmpty() ? null : found.getFirst().getData().get("value").asJsonString().getValue();
    }

    protected static void crashAndRestart() throws Exception {
        TestUtils.releaseAllLocks();
        TransactionOperationHelper.cleanupOrphansAtStartup();
    }

    protected AdminTransactionEntry preparedSave(String dtxId, int seq, JsonObject document) throws Exception {
        return preparedOp(dtxId, seq, AdminTransactionEntry.OP_TYPE_SAVE, document);
    }

    protected AdminTransactionEntry preparedOp(String dtxId, int seq, String opType, JsonObject payload)
            throws Exception {
        final var op = new AdminTransactionEntry(dtxId, "client", seq, opType, TestGlobals.DB, TestGlobals.COLL,
                payload);
        op.setVersions(List.of(clock.next()));
        AdminOperationHelper.saveTransactionOp(op);
        return op;
    }

    protected void markPrepared(String dtxId) throws Exception {
        Tx2pcLog.recordParticipantPrepared(dtxId, COORDINATOR, List.of(COORDINATOR), List.of(collId));
    }
}
