package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.MembershipView;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterRouterForwardedTxTest extends RemoteOwnerTxTestBase {
    private static final String UNREACHABLE_OWNER = "127.0.0.1:1";
    private static final long SHORT_WAIT_MS = 300L;
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @Override
    String collectionPrefix() {
        return "sole-owner-";
    }

    @BeforeEach
    public void startTransaction() {
        processor.processMessage(new StartTransactionRequest(), clientId);
    }

    @Override
    void releaseSessions() throws Exception {
        super.releaseSessions();
        for (final var sessionId : clientTracker.txSessionsSnapshot().keySet()) {
            clientTracker.removeTxSession(sessionId);
        }
    }

    private String route(OperationRequest request) {
        return router.forward(request, eJson.toJson(request), true, "admin", clientId);
    }

    private OperationResponse routed(OperationRequest request) {
        return eJson.fromJson(route(request), OperationResponse.class);
    }

    private String bufferRemoteSave(String id) {
        final var save = new SaveRequest(TestGlobals.DB, coll);
        save.setObject(document(id));
        save.set_id(id);
        return route(save);
    }

    private OperationResponse saveRemotely(String id) {
        return eJson.fromJson(bufferRemoteSave(id), OperationResponse.class);
    }

    private OperationResponse findLostRemotely() {
        final var find = new FindByIdRequest(TestGlobals.DB, coll);
        find.set_id("lost");
        return routed(find);
    }

    private String errorCodeOf(String relayed) {
        return eJson.fromJson(relayed, OperationResponse.class).getErrorCode();
    }

    private String fenceTheRemoteCommit(String id) throws Exception {
        bufferRemoteSave(id);
        makeTheOwnerTheOnlyNode();
        removeCollectionFolder();
        return route(new CommitTransactionRequest());
    }

    private boolean edgeStillHoldsTheTransaction() {
        return clientTracker.getActiveTransaction(clientId) != null
                && !clientTracker.transactionParticipants(clientId).isEmpty();
    }

    @Test
    public void a_fenced_forwarded_commit_keeps_the_edge_transaction_and_a_resent_commit_finishes_it()
            throws Exception {
        final var relayed = fenceTheRemoteCommit("fenced");

        assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(), errorCodeOf(relayed));
        assertTrue(edgeStillHoldsTheTransaction(),
                "the edge must keep the transaction so a re-sent COMMIT reaches the owner holding the slice");
        assertFalse(clientTracker.txSessionsSnapshot().isEmpty(), "the owner keeps the fenced session");

        fs.createCollectionFile(TestGlobals.DB, coll);
        final var resent = route(new CommitTransactionRequest());

        assertNull(errorCodeOf(resent), "the re-sent COMMIT must finish the slice, got: " + resent);
        assertEquals(OperationStatus.OK, findStatus("fenced"));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty(), "finishing the slice releases the owner's session");
    }

    @Test
    public void a_rollback_after_a_fenced_forwarded_commit_reports_half_applied() throws Exception {
        fenceTheRemoteCommit("rolled");

        final var relayed = route(new RollbackTransactionRequest());

        assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(), errorCodeOf(relayed),
                "a rollback must not report success for a slice recovery will still commit");
        assertTrue(edgeStillHoldsTheTransaction());
        final var session = clientTracker.txSessionsSnapshot().values().iterator().next();
        final var ownerTransaction = clientTracker.getActiveTransaction(session.clientId());
        assertNotNull(ownerTransaction, "the owner keeps the fenced slice");
        assertTrue(TxCommitLog.isLocallyCommitted(ownerTransaction.getTransactionId().toString()));
    }

    @Test
    public void an_ordinary_forwarded_commit_still_clears_the_edge() throws Exception {
        bufferRemoteSave("ordinary");
        makeTheOwnerTheOnlyNode();

        final var relayed = route(new CommitTransactionRequest());

        assertNull(errorCodeOf(relayed), "expected a clean commit, got: " + relayed);
        assertEquals(OperationStatus.OK, findStatus("ordinary"));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    @Test
    public void an_ordinary_forwarded_rollback_still_clears_the_edge() {
        bufferRemoteSave("discarded");

        final var relayed = route(new RollbackTransactionRequest());

        assertNull(errorCodeOf(relayed), "expected a clean rollback, got: " + relayed);
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
        assertEquals(OperationStatus.NOT_FOUND, findStatus("discarded"));
    }

    @Test
    public void an_unreachable_owner_still_clears_the_edge_on_commit() {
        clientTracker.addTransactionParticipant(clientId, UNREACHABLE_OWNER);

        final var relayed = route(new CommitTransactionRequest());

        assertEquals(ErrorCode.OWNER_UNREACHABLE.getCode(), errorCodeOf(relayed));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
    }

    @Test
    public void the_owner_holds_the_slice_only_on_a_fenced_or_indeterminate_answer() throws Exception {
        final Method decides = ClusterRouter.class.getDeclaredMethod("ownerStillHoldsTheSlice", String.class);
        decides.setAccessible(true);
        final var commit = OperationType.COMMIT_TRANSACTION;

        assertTrue((boolean) decides.invoke(router,
                eJson.toJson(new OperationResponse(commit, ErrorCode.TRANSACTION_HALF_APPLIED))));
        assertTrue((boolean) decides.invoke(router,
                eJson.toJson(new OperationResponse(commit, ErrorCode.TRANSACTION_INDETERMINATE))));
        assertTrue((boolean) decides.invoke(router, "not json"),
                "an unreadable answer keeps the transaction, since keeping it only lets the client retry");
        assertFalse((boolean) decides.invoke(router, eJson.toJson(OperationResponse.ok(commit, "committed"))));
        assertFalse((boolean) decides.invoke(router,
                eJson.toJson(new OperationResponse(commit, ErrorCode.NO_ACTIVE_TRANSACTION))));
    }

    private void participantLosesTheSlice() {
        TransactionOperationHelper.reapTransactionsForDeparted(new MembershipView(List.of()));
    }

    @Test
    public void test_the_first_write_to_an_owner_starts_its_slice() throws Exception {
        assertNull(saveRemotely("first").getErrorCode());
        assertNull(saveRemotely("second").getErrorCode(), "a continuation of a live slice is served");
        makeTheOwnerTheOnlyNode();

        assertNull(routed(new CommitTransactionRequest()).getErrorCode());
        assertEquals(OperationStatus.OK, findStatus("first"));
        assertEquals(OperationStatus.OK, findStatus("second"));
    }

    @Test
    public void test_a_write_after_the_owner_lost_the_slice_is_refused() {
        saveRemotely("lost");
        participantLosesTheSlice();

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), saveRemotely("after").getErrorCode());
        assertNotEquals(OperationStatus.OK, routed(new CommitTransactionRequest()).getStatus(),
                "a commit must never report the half of the transaction that survived as the whole");
        assertEquals(OperationStatus.NOT_FOUND, findStatus("lost"));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("after"));
    }

    @Test
    public void test_a_read_after_the_owner_lost_the_slice_is_refused() {
        saveRemotely("lost");
        participantLosesTheSlice();

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), findLostRemotely().getErrorCode(),
                "a read would otherwise start a fresh slice that no longer sees the transaction's own write");
    }

    private OperationResponse commitThatOutlivesTheOwnersWait() throws Exception {
        makeTheOwnerTheOnlyNode();
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", SHORT_WAIT_MS);
        locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        final OperationResponse first;
        try {
            first = routed(new CommitTransactionRequest());
        } finally {
            locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        }
        awaitTheOwnersLateFinish();
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 10_000L);
        return first;
    }

    private void awaitTheOwnersLateFinish() throws Exception {
        for (final var session : clientTracker.txSessionsSnapshot().values()) {
            session.submit(() -> null).get(10, TimeUnit.SECONDS);
        }
        final var txId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
        assertTrue(org.techhouse.ops.Tx2pcLog.hasOutcome(txId),
                "the owner finished the commit it stopped waiting for and recorded how it ended");
    }

    @Test
    public void test_a_resent_commit_after_the_owner_finished_late_answers_the_commit() throws Exception {
        saveRemotely("late");

        assertEquals(ErrorCode.TRANSACTION_INDETERMINATE.getCode(), commitThatOutlivesTheOwnersWait().getErrorCode());
        final var resent = routed(new CommitTransactionRequest());

        assertEquals(OperationStatus.OK, resent.getStatus(),
                "the transaction committed, so a re-send must not be told to roll back and retry");
        assertEquals(OperationStatus.OK, findStatus("late"));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty(), "the finished session is removed once answered");
    }

    @Test
    public void test_a_rollback_after_the_owner_finished_late_answers_already_committed() throws Exception {
        saveRemotely("late");
        commitThatOutlivesTheOwnersWait();

        final var rollback = routed(new RollbackTransactionRequest());

        assertEquals(ErrorCode.TRANSACTION_ALREADY_COMMITTED.getCode(), rollback.getErrorCode());
        assertEquals(OperationStatus.OK, findStatus("late"));
        assertNull(clientTracker.getActiveTransaction(clientId), "the transaction is over on the edge too");
    }
}
