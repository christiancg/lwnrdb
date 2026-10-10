package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.TransactionRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TwoPhaseParticipantFencingTest {

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

    private UUID participantWithAnUnapplicableOp() throws Exception {
        final var clientId = clientTracker.registerForwardedClient("participant");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("fenced"));
        request.set_id("fenced");
        TransactionOperationHelper.bufferSave(request, transaction);
        final var corrupt = new AdminTransactionEntry(transaction.getTransactionId().toString(), "client", 99,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, new JsonObject());
        AdminOperationHelper.saveTransactionOp(corrupt);
        transaction.getBufferedOpIds().add(corrupt.get_id());
        return clientId;
    }

    @Test
    public void test_a_commit_that_cannot_finish_applying_keeps_its_locks() throws Exception {
        final var clientId = participantWithAnUnapplicableOp();
        final var transaction = clientTracker.getActiveTransaction(clientId);
        assertFalse(transaction.getHeldLocks().isEmpty(), "the buffered write should hold a lock");

        final var response = TwoPhaseParticipant.commitPrepared(clientId);

        assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(), response.getErrorCode(),
                "releasing the locks over a half-applied slice lets a foreign write land above preparedVersion,"
                        + " and the replay then fences that id out permanently");
        assertNotNull(clientTracker.getActiveTransaction(clientId),
                "the transaction stays registered so recovery can still name its locks");
        assertFalse(transaction.getHeldLocks().isEmpty(), "the collections stay fenced until recovery finishes");

        TestUtils.releaseAllLocks();
        clientTracker.clearActiveTransaction(clientId);
        clientTracker.clearTransactionState(clientId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_a_disconnect_does_not_delete_a_fenced_2pc_slice() throws Exception {
        final var clientId = participantWithAnUnapplicableOp();
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var bufferedOpIds = List.copyOf(transaction.getBufferedOpIds());
        assertTrue(TwoPhaseParticipant.prepare(clientId, "127.0.0.1:9999", List.of("127.0.0.1:9999")),
                "the participant must reach PREPARED for the fence to be recorded");

        final var response = TwoPhaseParticipant.commitPrepared(clientId);
        assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(), response.getErrorCode());
        clientTracker.clearTransactionState(clientId);

        TransactionOperationHelper.cleanupOnDisconnect(clientId);

        assertEquals(bufferedOpIds.size(), AdminOperationHelper.readTransactionOps(bufferedOpIds).size(),
                "the slice the retained 2PC marker points at must survive the disconnect, or recovery replays"
                        + " nothing and records the transaction as committed");
        assertNotNull(clientTracker.getActiveTransaction(clientId),
                "a fenced transaction stays registered so its locks can still be named");

        Tx2pcLog.deleteParticipantMarker(transaction.getTransactionId().toString());
        AdminOperationHelper.deleteTransactionOps(bufferedOpIds);
        TestUtils.releaseAllLocks();
        clientTracker.clearActiveTransaction(clientId);
        clientTracker.clearTransactionState(clientId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_a_disconnect_still_cleans_up_an_unfenced_transaction() throws Exception {
        final var clientId = clientTracker.registerForwardedClient("participant");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("unfenced"));
        request.set_id("unfenced");
        TransactionOperationHelper.bufferSave(request, transaction);
        final var bufferedOpIds = List.copyOf(transaction.getBufferedOpIds());

        TransactionOperationHelper.cleanupOnDisconnect(clientId);

        assertTrue(AdminOperationHelper.readTransactionOps(bufferedOpIds).isEmpty(),
                "an ordinary open transaction is still rolled back on disconnect");
        assertNull(clientTracker.getActiveTransaction(clientId));
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_an_error_during_commit_prepared_keeps_the_fence() throws Exception {
        final var clientId = clientTracker.registerForwardedClient("participant");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("erroring"));
        request.set_id("erroring");
        TransactionOperationHelper.bufferSave(request, transaction);

        try (var recovery = mockStatic(TransactionRecovery.class)) {
            recovery.when(() -> TransactionRecovery.applyAllWithRetry(anyList(), anyString(), any()))
                    .thenThrow(new StackOverflowError("the apply blew the stack"));
            assertThrows(StackOverflowError.class, () -> TwoPhaseParticipant.commitPrepared(clientId));
        }

        assertNotNull(clientTracker.getActiveTransaction(clientId),
                "an Error past the commit point is still a half-applied commit, so the fence must hold");
        assertFalse(transaction.getHeldLocks().isEmpty(), "the collections stay fenced until recovery finishes");

        AdminOperationHelper.deleteTransactionOps(List.copyOf(transaction.getBufferedOpIds()));
        TestUtils.releaseAllLocks();
        clientTracker.clearActiveTransaction(clientId);
        clientTracker.clearTransactionState(clientId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_a_commit_that_applies_cleanly_still_releases_its_locks() {
        final var clientId = clientTracker.registerForwardedClient("participant");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("clean"));
        request.set_id("clean");
        TransactionOperationHelper.bufferSave(request, transaction);

        final var response = TwoPhaseParticipant.commitPrepared(clientId);

        assertEquals(org.techhouse.ops.OperationStatus.OK, response.getStatus(),
                "the fencing path must not disturb an ordinary prepared commit");
        assertTrue(transaction.getHeldLocks().isEmpty(), "a finished commit releases everything it took");
        clientTracker.removeById(clientId);
    }

    private static org.techhouse.cluster.ClusterCoordinator swapCoordinator(
            org.techhouse.cluster.ClusterCoordinator replacement) throws Exception {
        final var field = TransactionOperationHelper.class.getDeclaredField("coordinator");
        field.setAccessible(true);
        final var original = (org.techhouse.cluster.ClusterCoordinator) field.get(null);
        field.set(null, replacement);
        return original;
    }

    private boolean prepareWithOwnership(String id, boolean stillOwner) throws Exception {
        final var clientId = clientTracker.registerForwardedClient("participant");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        TransactionOperationHelper.bufferSave(request, transaction);
        final var coordinator = org.mockito.Mockito.mock(org.techhouse.cluster.ClusterCoordinator.class);
        org.mockito.Mockito.when(coordinator.stillOwns(TestGlobals.DB, TestGlobals.COLL)).thenReturn(stillOwner);
        final var original = swapCoordinator(coordinator);
        try {
            return TwoPhaseParticipant.prepare(clientId, "127.0.0.1:5000", List.of("127.0.0.1:5000"));
        } finally {
            swapCoordinator(original);
            TransactionOperationHelper.abort(clientId);
            clientTracker.removeById(clientId);
            final var txId = transaction.getTransactionId().toString();
            if (Tx2pcLog.isPrepared(txId)) {
                Tx2pcLog.deleteParticipantMarker(txId);
            }
        }
    }

    @Test
    public void test_prepare_votes_no_when_ownership_moved() throws Exception {
        assertFalse(prepareWithOwnership("moved", false),
                "a participant that no longer owns a collection it buffered must not vote to commit it");
    }

    @Test
    public void test_prepare_votes_yes_when_still_owner() throws Exception {
        assertTrue(prepareWithOwnership("kept", true));
    }

    @Test
    public void test_a_prepared_commit_records_trigger_runs_before_deleting_its_ops() throws Exception {
        final var configuration = Configuration.getInstance();
        final var cache = IocContainer.get(Cache.class);
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("t", new LinkedHashSet<>(Set.of(EventType.CREATED)), "recalc",
                        TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner")));
        final var clientId = clientTracker.registerForwardedClient("participant");
        TransactionOperationHelper.start(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("staged-2pc"));
        request.set_id("staged-2pc");
        TransactionOperationHelper.bufferSave(request, clientTracker.getActiveTransaction(clientId));
        final var calls = new CopyOnWriteArrayList<String>();
        final Answer<Object> recording = invocation -> {
            calls.add(invocation.getMethod().getName());
            return invocation.callRealMethod();
        };
        try (var ignoredAdmin = mockStatic(AdminOperationHelper.class, recording);
                var ignoredRunLog = mockStatic(TriggerRunLog.class, recording)) {
            TwoPhaseParticipant.commitPrepared(clientId);
        } finally {
            cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
            TestUtils.setPrivateField(configuration, "triggersEnabled", false);
            clientTracker.removeById(clientId);
        }

        final var recorded = calls.indexOf("recordDeterministic");
        assertTrue(recorded >= 0, "the prepared commit recorded no trigger run: " + calls);
        assertTrue(recorded < calls.lastIndexOf("deleteTransactionOps"), "recorded after its ops were gone: " + calls);
    }
}
