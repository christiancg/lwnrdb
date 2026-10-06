package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Transaction;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionOpCleanupOrderTest {
    private static final String TRIGGER = "cleanup-order";
    private static final String DELETE_OPS = "deleteTransactionOps";
    private static final String CLEAR_LOCAL_COMMIT = "clearLocalCommit";
    private static final String TRANSIENT_ID = "transient";
    private static final Configuration configuration = Configuration.getInstance();

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        for (final var run : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(run.getRunId(), run.getTriggerName());
        }
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
        TransactionOperationHelper.start(clientId);
        return clientId;
    }

    private Transaction transactionOf(UUID clientId) {
        return clientTracker.getActiveTransaction(clientId);
    }

    private void save(UUID clientId, String id) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
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

    private static boolean deletesTheSlice(InvocationOnMock invocation, List<String> opIds) {
        return DELETE_OPS.equals(invocation.getMethod().getName()) && opIds.equals(invocation.getArgument(0));
    }

    private static Answer<Object> killedAfterTheFirstOpOf(List<String> opIds) {
        return invocation -> {
            if (!deletesTheSlice(invocation, opIds)) {
                return invocation.callRealMethod();
            }
            AdminOperationHelper.deleteTransactionOps(List.of(opIds.getFirst()));
            throw new IOException("killed after deleting the first op");
        };
    }

    private boolean anyOpLeft(List<String> opIds) {
        return opIds.stream().anyMatch(id -> cache.getTransactionPkIndexes().containsKey(id));
    }

    @Test
    public void test_commit_clears_its_marker_before_deleting_its_ops() {
        final var clientId = openTransaction();
        save(clientId, "order-a");
        save(clientId, "order-b");
        final var opIds = List.copyOf(transactionOf(clientId).getBufferedOpIds());
        final var calls = new CopyOnWriteArrayList<String>();
        final Answer<Object> recording = invocation -> {
            calls.add(deletesTheSlice(invocation, opIds) ? "slice" : invocation.getMethod().getName());
            return invocation.callRealMethod();
        };

        try (var ignoredAdmin = mockStatic(AdminOperationHelper.class, recording);
                var ignoredCommitLog = mockStatic(TxCommitLog.class, recording)) {
            assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        }

        assertTrue(calls.contains(CLEAR_LOCAL_COMMIT), "the commit never cleared its marker: " + calls);
        assertTrue(calls.indexOf(CLEAR_LOCAL_COMMIT) < calls.indexOf("slice"),
                "the ops were deleted while the marker could still replay them: " + calls);
    }

    @Test
    public void test_a_kill_midway_through_op_deletion_keeps_the_whole_batch_run() throws Exception {
        triggerOn(EventType.CREATED, TriggerDefinition.MODE_BATCH);
        final var clientId = openTransaction();
        save(clientId, "batch-a");
        save(clientId, "batch-b");
        save(clientId, "batch-c");
        final var opIds = List.copyOf(transactionOf(clientId).getBufferedOpIds());

        try (var ignored = mockStatic(AdminOperationHelper.class, killedAfterTheFirstOpOf(opIds))) {
            assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        }
        TransactionOperationHelper.cleanupOrphansAtStartup();

        final var runs = TriggerRunLog.pending();
        assertEquals(1, runs.size());
        assertEquals(Set.of("batch-a", "batch-b", "batch-c"), Set.copyOf(runs.getFirst().getIds()),
                "the batch run lost ids to a narrowed replay");
        assertFalse(anyOpLeft(opIds), "the startup sweep must remove the leftover op records");
    }

    @Test
    public void test_a_kill_midway_through_op_deletion_fires_no_deleted_for_a_created_then_deleted_id()
            throws Exception {
        triggerOn(EventType.DELETED, TriggerDefinition.MODE_DOCUMENT);
        final var clientId = openTransaction();
        save(clientId, TRANSIENT_ID);
        deleteTransientDocument(clientId);
        final var opIds = List.copyOf(transactionOf(clientId).getBufferedOpIds());

        try (var ignored = mockStatic(AdminOperationHelper.class, killedAfterTheFirstOpOf(opIds))) {
            assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        }
        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertTrue(TriggerRunLog.pending().isEmpty(), "no reader outside the transaction ever saw that document");
    }

    @Test
    public void test_a_failed_op_deletion_still_commits_and_releases_the_locks() throws Exception {
        triggerOn(EventType.CREATED, TriggerDefinition.MODE_DOCUMENT);
        final var clientId = openTransaction();
        save(clientId, "undeletable");
        final var transaction = transactionOf(clientId);
        final var txId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());

        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if (deletesTheSlice(invocation, opIds)) {
                throw new IOException("disk full");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        }

        assertFalse(TxCommitLog.isLocallyCommitted(txId));
        assertFalse(locks.holdsCollectionLock(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(1, TriggerRunLog.pending().size());
        assertTrue(anyOpLeft(opIds));
        TransactionOperationHelper.cleanupOrphansAtStartup();
        assertFalse(anyOpLeft(opIds));
    }

    @Test
    public void test_a_replay_clears_its_marker_before_deleting_its_ops() throws Exception {
        final var clientId = openTransaction();
        save(clientId, "replayed");
        final var txId = transactionOf(clientId).getTransactionId().toString();
        final var opIds = List.copyOf(transactionOf(clientId).getBufferedOpIds());
        final var failuresLeft = new AtomicInteger(1);
        try (var ignored = mockStatic(TxCommitLog.class, invocation -> {
            if (CLEAR_LOCAL_COMMIT.equals(invocation.getMethod().getName()) && failuresLeft.getAndDecrement() > 0) {
                throw new IOException("killed before the marker was cleared");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(),
                    TransactionOperationHelper.commit(clientId).getErrorCode());
        }
        final var calls = new CopyOnWriteArrayList<String>();
        final var deletionFailuresLeft = new AtomicInteger(1);

        try (var ignoredAdmin = mockStatic(AdminOperationHelper.class, invocation -> {
            if (deletesTheSlice(invocation, opIds) && deletionFailuresLeft.getAndDecrement() > 0) {
                calls.add("slice");
                throw new IOException("disk full");
            }
            return invocation.callRealMethod();
        }); var ignoredCommitLog = mockStatic(TxCommitLog.class, invocation -> {
            calls.add(invocation.getMethod().getName());
            return invocation.callRealMethod();
        })) {
            TransactionOperationHelper.cleanupOrphansAtStartup();
        }

        assertTrue(calls.contains(CLEAR_LOCAL_COMMIT), "the replay never cleared its marker: " + calls);
        assertTrue(calls.indexOf(CLEAR_LOCAL_COMMIT) < calls.indexOf("slice"), calls.toString());
        assertFalse(TxCommitLog.isLocallyCommitted(txId), "the replay must still report the commit finished");
        assertFalse(locks.holdsCollectionLock(TestGlobals.DB, TestGlobals.COLL));
        assertFalse(anyOpLeft(opIds), "the orphan sweep after the replay removes what the replay left");
    }

    @Test
    public void test_a_failed_marker_clear_still_fences_the_commit() throws Exception {
        final var clientId = openTransaction();
        save(clientId, "fenced-a");
        save(clientId, "fenced-b");
        final var txId = transactionOf(clientId).getTransactionId().toString();
        final var opIds = List.copyOf(transactionOf(clientId).getBufferedOpIds());

        try (var ignored = mockStatic(TxCommitLog.class, invocation -> {
            if (CLEAR_LOCAL_COMMIT.equals(invocation.getMethod().getName())) {
                throw new IOException("marker unclearable");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(),
                    TransactionOperationHelper.commit(clientId).getErrorCode());
        }

        assertTrue(TxCommitLog.isLocallyCommitted(txId));
        assertTrue(opIds.stream().allMatch(id -> cache.getTransactionPkIndexes().containsKey(id)),
                "a replay needs every op while the marker stands");
        TransactionOperationHelper.cleanupOrphansAtStartup();
        assertFalse(TxCommitLog.isLocallyCommitted(txId));
    }
}
