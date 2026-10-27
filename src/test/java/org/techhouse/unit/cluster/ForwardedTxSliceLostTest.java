package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.Tx2pcRecovery;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;

public class ForwardedTxSliceLostTest extends ClusterConnectionHandlerTestBase {
    private static final String COORDINATOR = "127.0.0.1:5000";

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final String sessionId = UUID.randomUUID().toString();
    private final String txId = UUID.randomUUID().toString();
    private final String otherTxId = UUID.randomUUID().toString();

    @AfterEach
    public void removeSession() {
        clientTracker.removeTxSession(sessionId);
    }

    private ClusterMessage forwarded(String id, String body, boolean continuation) {
        final var message = envelope(ClusterMessageType.FORWARD_TX_REQUEST);
        message.setActingUser("admin");
        message.setTxSessionId(sessionId);
        message.setTxId(id);
        message.setTxContinuation(continuation);
        message.setForwardBody(body);
        return message;
    }

    private static String save(String id) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"" + id + "\",\"v\":1}}";
    }

    private static String findFirst() {
        return "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"_id\":\"first\"}";
    }

    private static String commit() {
        return "{\"type\":\"COMMIT_TRANSACTION\"}";
    }

    private OperationResponse send(String id, String body, boolean continuation) throws Exception {
        final var reply = pool.request(cluster.serverAddress(), forwarded(id, body, continuation), ACK_TIMEOUT_MS);
        assertEquals(ClusterMessageType.FORWARD_RESPONSE, reply.getType(), reply.getErrorMessage());
        return eJson.fromJson(reply.getForwardBody(), OperationResponse.class);
    }

    private ClusterMessage prepare() throws Exception {
        final var message = envelope(ClusterMessageType.PREPARE_TX);
        message.setTxSessionId(sessionId);
        message.setTxId(txId);
        message.setTxParticipants(List.of(COORDINATOR));
        return pool.request(cluster.serverAddress(), message, ACK_TIMEOUT_MS);
    }

    @Test
    public void test_a_continuation_with_no_slice_is_refused_and_starts_nothing() throws Exception {
        final var refused = send(txId, save("after-loss"), true);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), refused.getErrorCode());
        assertNull(clientTracker.txSession(sessionId), "the session registered for the refused op holds nothing");
        assertEquals(OperationStatus.NOT_FOUND, findById("after-loss").getStatus());
    }

    @Test
    public void test_a_first_forward_still_starts_the_transaction() throws Exception {
        assertEquals(OperationStatus.OK, send(txId, save("first"), false).getStatus());

        assertEquals(OperationStatus.OK, send(txId, commit(), true).getStatus());
        assertEquals(OperationStatus.OK, findById("first").getStatus());
    }

    @Test
    public void test_a_continuation_of_the_live_slice_is_served() throws Exception {
        send(txId, save("first"), false);

        assertEquals(OperationStatus.OK, send(txId, save("second"), true).getStatus());
        assertEquals(OperationStatus.OK, send(txId, findFirst(), true).getStatus());
        assertEquals(OperationStatus.OK, send(txId, commit(), true).getStatus());

        assertEquals(OperationStatus.OK, findById("first").getStatus());
        assertEquals(OperationStatus.OK, findById("second").getStatus());
    }

    @Test
    public void test_a_slice_reaped_for_a_departed_edge_refuses_the_edges_next_op() throws Exception {
        send(txId, save("before-partition"), false);

        TransactionOperationHelper.reapTransactionsForDeparted(new MembershipView(List.of()));
        final var refused = send(txId, save("after-partition"), true);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), refused.getErrorCode(),
                "committing only the writes sent after the reap would report half a transaction as committed");
        assertEquals(ClusterMessageType.ERROR, prepare().getType(), "the participant must vote no");
        assertFalse(Tx2pcLog.isPrepared(txId));
        assertEquals(OperationStatus.NOT_FOUND, findById("before-partition").getStatus());
        assertEquals(OperationStatus.NOT_FOUND, findById("after-partition").getStatus());
    }

    @Test
    public void test_a_sole_slice_commit_after_a_reap_does_not_report_success() throws Exception {
        send(txId, save("reaped"), false);
        TransactionOperationHelper.reapTransactionsForDeparted(new MembershipView(List.of()));

        final var commit = send(txId, commit(), true);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), commit.getErrorCode());
        assertEquals(OperationStatus.NOT_FOUND, findById("reaped").getStatus());
    }

    private static String rollback() {
        return "{\"type\":\"ROLLBACK_TRANSACTION\"}";
    }

    private void finishedWith(String id, boolean committed, ErrorCode reply) throws Exception {
        clientTracker.registerTxSession(sessionId, "admin", null);
        Tx2pcLog.recordOutcome(id, committed, reply == null ? null : reply.getCode());
    }

    @Test
    public void test_a_rollback_after_a_commit_that_applied_nothing_answers_ok() throws Exception {
        finishedWith(txId, false, null);

        assertEquals(OperationStatus.OK, send(txId, rollback(), true).getStatus());
        assertNull(clientTracker.txSession(sessionId));
    }

    @Test
    public void test_a_rollback_after_a_commit_that_timed_out_replicating_answers_already_committed() throws Exception {
        finishedWith(txId, true, ErrorCode.REPLICATION_TIMEOUT);

        assertEquals(ErrorCode.TRANSACTION_ALREADY_COMMITTED.getCode(), send(txId, rollback(), true).getErrorCode(),
                "the local commit stands on a replication timeout");
    }

    @Test
    public void test_a_resent_rollback_answers_the_recorded_rollback() throws Exception {
        finishedWith(txId, false, null);

        assertEquals(OperationStatus.OK, send(txId, rollback(), true).getStatus());
    }

    @Test
    public void test_a_commit_after_a_finished_rollback_is_still_slice_lost() throws Exception {
        finishedWith(txId, false, null);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), send(txId, commit(), true).getErrorCode());
    }

    @Test
    public void test_a_finished_slice_never_answers_for_another_transaction() throws Exception {
        finishedWith(otherTxId, true, null);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), send(txId, commit(), true).getErrorCode());
    }

    @Test
    public void test_removing_the_session_keeps_its_recorded_outcome() throws Exception {
        finishedWith(txId, true, null);

        clientTracker.removeTxSession(sessionId);

        assertEquals(OperationStatus.OK, send(txId, commit(), true).getStatus(),
                "the edge may still re-send a commit whose answer never reached it");
    }

    @Test
    public void test_a_commit_whose_answer_was_lost_is_answered_by_its_outcome_on_resend() throws Exception {
        send(txId, save("lost-answer"), false);
        assertEquals(OperationStatus.OK, send(txId, commit(), true).getStatus());
        assertNull(clientTracker.txSession(sessionId), "the handler closed the session after the commit");

        final var resent = send(txId, commit(), true);

        assertEquals(OperationStatus.OK, resent.getStatus(), "the transaction committed; 409-13 would say otherwise");
        assertEquals(OperationStatus.OK, findById("lost-answer").getStatus());
    }

    @Test
    public void test_a_rollback_after_a_commit_whose_answer_was_lost_answers_already_committed() throws Exception {
        send(txId, save("lost-answer-rollback"), false);
        send(txId, commit(), true);

        assertEquals(ErrorCode.TRANSACTION_ALREADY_COMMITTED.getCode(), send(txId, rollback(), true).getErrorCode());
        assertEquals(OperationStatus.OK, findById("lost-answer-rollback").getStatus());
    }

    @Test
    public void test_a_continuation_that_finds_another_transactions_leftover_is_still_refused() throws Exception {
        send(otherTxId, save("leftover"), false);

        final var refused = send(txId, save("continued"), true);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), refused.getErrorCode(),
                "the leftover proves this transaction's own earlier op never reached the live slice");
        assertNull(clientTracker.txSession(sessionId));
        assertEquals(OperationStatus.NOT_FOUND, findById("leftover").getStatus());
        assertEquals(OperationStatus.NOT_FOUND, findById("continued").getStatus());
    }

    @Test
    public void test_a_resent_commit_that_cannot_finish_leaves_its_locks_to_the_recovery_thread() throws Exception {
        final var lateColl = "late-collection";
        final var document = new JsonObject();
        document.add("_id", new JsonString("late"));
        final var op = new AdminTransactionEntry(txId, "client", 0, AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB,
                lateColl, document);
        AdminOperationHelper.saveTransactionOp(op);
        TxCommitLog.recordLocalCommit(txId, List.of(op.get_id()),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));

        final var reply = pool.request(cluster.serverAddress(), forwarded(txId, commit(), true), ACK_TIMEOUT_MS);
        assertEquals(ClusterMessageType.ERROR, reply.getType(), "a replay that could not finish is never acked");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, lateColl)).getStatus());
        IocContainer.get(Tx2pcRecovery.class).recoverNow();

        assertFalse(TxCommitLog.isLocallyCommitted(txId),
                "the recovery thread finishes the slice, so the re-sent commit must not have kept its locks");
        final var find = new FindByIdRequest(TestGlobals.DB, lateColl);
        find.set_id("late");
        assertEquals(OperationStatus.OK, processor.processMessage(find).getStatus());
    }
}
