package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TwoPhaseParticipantOpCleanupTest {
    private static final String COORDINATOR = "127.0.0.1:9999";

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID preparedParticipantSaving(String... ids) {
        final var clientId = clientTracker.registerForwardedClient("participant-" + UUID.randomUUID());
        TransactionOperationHelper.start(clientId);
        for (final var id : ids) {
            final var object = new JsonObject();
            object.add(Globals.PK_FIELD, new JsonString(id));
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObject(object);
            request.set_id(id);
            assertEquals(OperationStatus.OK, TransactionOperationHelper
                    .bufferSave(request, clientTracker.getActiveTransaction(clientId)).getStatus());
        }
        assertTrue(TwoPhaseParticipant.prepare(clientId, COORDINATOR, List.of(COORDINATOR)));
        return clientId;
    }

    private static boolean deletesTheSlice(InvocationOnMock invocation, List<String> opIds) {
        return "deleteTransactionOps".equals(invocation.getMethod().getName())
                && opIds.equals(invocation.getArgument(0));
    }

    private boolean everyOpLeft(List<String> opIds) {
        return opIds.stream().allMatch(id -> cache.getTransactionPkIndexes().containsKey(id));
    }

    @Test
    public void test_commit_prepared_resolves_markers_before_deleting_its_ops() {
        final var clientId = preparedParticipantSaving("p-a", "p-b");
        final var opIds = List.copyOf(clientTracker.getActiveTransaction(clientId).getBufferedOpIds());
        final var calls = new CopyOnWriteArrayList<String>();
        final Answer<Object> recording = invocation -> {
            calls.add(deletesTheSlice(invocation, opIds) ? "slice" : invocation.getMethod().getName());
            return invocation.callRealMethod();
        };

        try (var ignoredAdmin = mockStatic(AdminOperationHelper.class, recording);
                var ignoredLog = mockStatic(Tx2pcLog.class, recording)) {
            assertEquals(OperationStatus.OK, TwoPhaseParticipant.commitPrepared(clientId).getStatus());
        }

        final var slice = calls.indexOf("slice");
        assertTrue(slice >= 0, "the commit never deleted its ops: " + calls);
        assertTrue(calls.indexOf("deleteParticipantMarker") < slice, calls.toString());
        assertTrue(calls.indexOf("recordOutcome") < slice, calls.toString());
    }

    @Test
    public void test_a_failed_op_deletion_after_resolution_answers_ok() throws Exception {
        final var clientId = preparedParticipantSaving("p-undeletable");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var dtxId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());

        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if (deletesTheSlice(invocation, opIds)) {
                throw new IOException("disk full");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(OperationStatus.OK, TwoPhaseParticipant.commitPrepared(clientId).getStatus());
        }

        assertFalse(Tx2pcLog.isPrepared(dtxId));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertFalse(locks.holdsCollectionLock(TestGlobals.DB, TestGlobals.COLL));
        AdminOperationHelper.deleteTransactionOps(opIds);
        Tx2pcLog.deleteOutcomeMarker(dtxId);
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_a_failed_marker_resolution_still_fences() throws Exception {
        final var clientId = preparedParticipantSaving("p-fenced-a", "p-fenced-b");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var dtxId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());

        try (var ignored = mockStatic(Tx2pcLog.class, invocation -> {
            if ("deleteParticipantMarker".equals(invocation.getMethod().getName())) {
                throw new IOException("marker unresolvable");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(),
                    TwoPhaseParticipant.commitPrepared(clientId).getErrorCode());
        }

        assertTrue(Tx2pcLog.isPrepared(dtxId));
        assertTrue(everyOpLeft(opIds), "recovery replays the slice the PREPARED marker points at");
        Tx2pcLog.deleteParticipantMarker(dtxId);
        AdminOperationHelper.deleteTransactionOps(opIds);
        TestUtils.releaseAllLocks();
        clientTracker.clearActiveTransaction(clientId);
        clientTracker.clearTransactionState(clientId);
        clientTracker.removeById(clientId);
    }
}
