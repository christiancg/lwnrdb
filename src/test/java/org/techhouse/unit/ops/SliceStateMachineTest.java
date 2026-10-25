package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.admin.TransactionRecords;
import org.techhouse.ops.tx.SliceCommit;
import org.techhouse.ops.tx.SliceStateMachine;
import org.techhouse.ops.tx.SliceStateMachine.Action;
import org.techhouse.ops.tx.SliceStateMachine.Message;
import org.techhouse.ops.tx.SliceStateMachine.State;
import org.techhouse.ops.tx.VersionedApply.ApplyOutcome;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SliceStateMachineTest extends VersionedReplaySupport {
    private static final String LOST = "409-13";
    private static final String FENCED = "500-33";
    private static final String COMMITTED_ALREADY = "409-14";
    private static final String PEER_ERROR = "";

    private static final String[][] EXPECTED = {{"NONE", "START_AND_RUN", LOST, LOST, LOST, "VOTE_NO", "ACK", "ACK"},
            {"ACTIVE", "RUN", "RUN", "COMMIT", "ABORT", "PREPARE", PEER_ERROR, "ABORT"},
            {"PREPARED", FENCED, FENCED, FENCED, FENCED, "VOTE_YES", "FINISH_COMMIT", "ABORT"},
            {"COMMITTING", FENCED, FENCED, "FINISH_COMMIT", FENCED, "VOTE_YES", "FINISH_COMMIT", PEER_ERROR},
            {"COMMITTED", LOST, LOST, "REPLAY_REPLY", COMMITTED_ALREADY, "VOTE_YES", "ACK", PEER_ERROR},
            {"ABORTED", LOST, LOST, LOST, "REPLAY_REPLY", "VOTE_NO", PEER_ERROR, "ACK"}};

    private static Executable cell(State state, Message message, String expected) {
        return () -> {
            final var decision = SliceStateMachine.decide(state, message);
            final var label = state + " x " + message;
            if (expected.isEmpty() || expected.contains("-")) {
                assertEquals(Action.REFUSE, decision.action(), label);
                assertEquals(expected.isEmpty() ? null : ErrorCode.byCode(expected), decision.refusal(), label);
            } else {
                assertEquals(Action.valueOf(expected), decision.action(), label);
                assertNull(decision.refusal(), label);
            }
        };
    }

    @Test
    public void test_every_state_and_message_is_decided_as_the_table_says() {
        final List<Executable> cells = new ArrayList<>();
        for (final var row : EXPECTED) {
            final var state = State.valueOf(row[0]);
            for (final var message : Message.values()) {
                cells.add(cell(state, message, row[message.ordinal() + 1]));
            }
        }
        assertEquals(State.values().length * Message.values().length, cells.size());
        assertAll(cells);
    }

    private String prepared() throws Exception {
        final var txId = UUID.randomUUID().toString();
        markPrepared(txId);
        return txId;
    }

    @Test
    public void test_outcome_outranks_every_other_marker() throws Exception {
        final var txId = prepared();
        TxCommitLog.recordLocalCommit(txId, List.of(), List.of(collId));
        Tx2pcLog.recordOutcome(txId, true, null);

        assertEquals(State.COMMITTED, SliceStateMachine.stateOf(txId, null));
    }

    @Test
    public void test_localcommit_outranks_part() throws Exception {
        final var txId = prepared();
        TxCommitLog.recordLocalCommit(txId, List.of(), List.of(collId));

        assertEquals(State.COMMITTING, SliceStateMachine.stateOf(txId, null));
        assertTrue(SliceStateMachine.isFenced(txId));
    }

    @Test
    public void test_a_prepared_slice_is_fenced() throws Exception {
        final var txId = prepared();

        assertEquals(State.PREPARED, SliceStateMachine.stateOf(txId, null));
        assertTrue(SliceStateMachine.isFenced(txId));
    }

    @Test
    public void test_a_live_transaction_with_no_marker_is_active() throws Exception {
        final var transaction = newTransaction();
        final var txId = transaction.getTransactionId().toString();

        assertEquals(State.ACTIVE, SliceStateMachine.stateOf(txId, transaction));
        assertEquals(State.NONE, SliceStateMachine.stateOf(UUID.randomUUID().toString(), transaction),
                "a live transaction under another id says nothing about this one");
        assertFalse(SliceStateMachine.isFenced(txId));
    }

    @Test
    public void test_no_marker_and_no_transaction_is_none() throws Exception {
        assertEquals(State.NONE, SliceStateMachine.stateOf(UUID.randomUUID().toString(), null));
    }

    @Test
    public void test_an_aborted_outcome_reads_as_aborted() throws Exception {
        final var txId = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(txId, false, null);

        assertEquals(State.ABORTED, SliceStateMachine.stateOf(txId, null));
    }

    @Test
    public void test_the_recorded_reply_round_trips() throws Exception {
        final var timedOut = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(timedOut, true, ErrorCode.REPLICATION_TIMEOUT.getCode());
        final var rolledBack = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(rolledBack, false, null);

        assertEquals(ErrorCode.REPLICATION_TIMEOUT.getCode(),
                SliceStateMachine.recordedReply(timedOut, OperationType.COMMIT_TRANSACTION).getErrorCode());
        assertEquals(OperationStatus.OK,
                SliceStateMachine.recordedReply(rolledBack, OperationType.ROLLBACK_TRANSACTION).getStatus());
        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), SliceStateMachine
                .recordedReply(UUID.randomUUID().toString(), OperationType.COMMIT_TRANSACTION).getErrorCode());
    }

    @Test
    public void test_no_restart_turns_a_decided_slice_into_a_lost_one() throws Exception {
        final var committing = prepared();
        TxCommitLog.recordLocalCommit(committing, List.of(), List.of(collId));
        final var committed = UUID.randomUUID().toString();
        Tx2pcLog.recordOutcome(committed, true, null);

        for (final var txId : List.of(committing, committed)) {
            final var decision = SliceStateMachine.decide(SliceStateMachine.stateOf(txId, null), Message.COMMIT);
            assertFalse(decision.action() == Action.REFUSE && decision.refusal() == ErrorCode.TRANSACTION_SLICE_LOST,
                    "a re-sent COMMIT after a restart must learn the decided outcome, not that the slice was lost");
        }
    }

    private List<AdminTransactionEntry> opsOf(Transaction transaction) throws Exception {
        final var ops = new ArrayList<>(AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds()));
        ops.sort(Comparator.comparingLong(AdminTransactionEntry::getSeq));
        return ops;
    }

    @Test
    public void test_begin_writes_localcommit_and_drops_part() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "begun", "v");
        final var txId = transaction.getTransactionId().toString();
        markPrepared(txId);

        SliceCommit.begin(transaction);

        assertTrue(TxCommitLog.isLocallyCommitted(txId));
        assertFalse(Tx2pcLog.isPrepared(txId));
        assertEquals(transaction.getBufferedOpIds(),
                Objects.requireNonNull(TxCommitLog.readLocalCommitMarker(txId)).opIds());
    }

    @Test
    public void test_begin_from_durable_carries_the_prepared_slice_over() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        final var op = preparedSave(dtxId, 0, document("durable", "v"));
        markPrepared(dtxId);

        SliceCommit.beginFromDurable(dtxId);

        final var marker = TxCommitLog.readLocalCommitMarker(dtxId);
        assertNotNull(marker);
        assertEquals(List.of(op.get_id()), marker.opIds());
        assertEquals(List.of(collId), marker.collections());
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }

    @Test
    public void test_finish_writes_the_outcome_only_when_asked() throws Exception {
        final var recorded = newTransaction();
        bufferSave(recorded, "recorded", "v");
        SliceCommit.begin(recorded);
        final var silent = newTransaction();
        bufferSave(silent, "silent", "v");
        SliceCommit.begin(silent);

        assertEquals(SliceCommit.Result.COMMITTED, SliceCommit.finish(recorded, opsOf(recorded), "admin", true));
        assertEquals(SliceCommit.Result.COMMITTED, SliceCommit.finish(silent, opsOf(silent), "admin", false));

        assertTrue(Objects.requireNonNull(Tx2pcLog.readOutcome(recorded.getTransactionId().toString())).committed());
        assertNull(Tx2pcLog.readOutcome(silent.getTransactionId().toString()));
        assertFalse(TxCommitLog.isLocallyCommitted(recorded.getTransactionId().toString()));
        assertEquals("v", valueOf("recorded"));
    }

    @Test
    public void test_status_answers_committed_while_finish_unpublishes() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "unpublished", "v");
        final var txId = transaction.getTransactionId().toString();
        SliceCommit.begin(transaction);
        markPrepared(txId);
        final var ops = opsOf(transaction);
        final var statuses = new ArrayList<String>();
        final var spy = Mockito.spy(cache);
        final Answer<Object> observing = invocation -> {
            invocation.callRealMethod();
            statuses.add(invocation.getMethod().getName() + "=" + Tx2pcLog.status(txId, null));
            return null;
        };
        Mockito.doAnswer(observing).when(spy).putPkIndexTransaction(Mockito.any());
        Mockito.doAnswer(observing).when(spy).removePkIndexTransaction(Mockito.anyString());
        TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", spy);
        try {
            assertEquals(SliceCommit.Result.COMMITTED, SliceCommit.finish(transaction, ops, "admin", true));
        } finally {
            TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", cache);
        }

        assertEquals(2 + ops.size() + 1, statuses.size(), "outcome, part, localcommit and every op: " + statuses);
        assertTrue(statuses.stream().allMatch(status -> status.endsWith("=COMMITTED")), statuses.toString());
        assertTrue(statuses.getFirst().startsWith("putPkIndexTransaction"), "the outcome is published first");
    }

    @Test
    public void test_unpublish_order_drops_part_then_localcommit_then_the_rest() {
        final var txId = UUID.randomUUID().toString();
        final var op = txId + "|0";
        final var part = Tx2pcLog.participantMarkerId(txId);
        final var localCommit = TxCommitLog.markerId(txId);

        assertEquals(List.of(part, localCommit, op), TransactionRecords.unpublishOrder(List.of(op, localCommit, part)));
    }

    @Test
    public void test_a_failed_apply_stays_committing() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "stuck", "v");
        SliceCommit.begin(transaction);
        final var ops = opsOf(transaction);
        final var unreachable = new AdminTransactionEntry(transaction.getTransactionId().toString(), "client", 9,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, "no-such-collection", document("x", "v"));
        ops.add(unreachable);

        assertEquals(SliceCommit.Result.HALF_APPLIED, SliceCommit.finish(transaction, ops, "admin", true));

        assertTrue(TxCommitLog.isLocallyCommitted(transaction.getTransactionId().toString()));
        assertNull(Tx2pcLog.readOutcome(transaction.getTransactionId().toString()));
    }

    @Test
    public void test_finish_is_idempotent_when_run_twice() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "twice", "one");
        bufferSave(transaction, "twice", "two");
        SliceCommit.begin(transaction);
        final var ops = opsOf(transaction);

        assertEquals(SliceCommit.Result.COMMITTED, SliceCommit.finish(transaction, ops, "admin", false));
        TxCommitLog.recordLocalCommit(transaction.getTransactionId().toString(), transaction.getBufferedOpIds(),
                List.of(collId));
        assertEquals(SliceCommit.Result.COMMITTED, SliceCommit.finish(transaction, ops, "admin", false));

        assertEquals("two", valueOf("twice"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"test_an_equal_stored_version_is_already_applied, SAVE, 7, 0, 7, ALREADY_APPLIED",
            "test_a_higher_stored_version_supersedes_a_save, SAVE, 8, 0, 7, SUPERSEDED",
            "test_a_tombstone_at_or_above_the_op_supersedes_a_save, SAVE, 0, 7, 7, SUPERSEDED",
            "test_a_tombstone_at_or_above_the_op_supersedes_a_save, SAVE, 0, 9, 7, SUPERSEDED",
            "test_an_older_stored_version_or_tombstone_lets_a_save_apply, SAVE, 6, 0, 7, APPLIED",
            "test_an_older_stored_version_or_tombstone_lets_a_save_apply, SAVE, 0, 6, 7, APPLIED",
            "test_an_absent_id_is_applied_for_a_save, SAVE, 0, 0, 7, APPLIED",
            "test_a_delete_of_an_absent_id_is_already_applied, DELETE, 0, 0, 7, ALREADY_APPLIED",
            "test_a_higher_stored_version_supersedes_a_delete, DELETE, 8, 1, 7, SUPERSEDED",
            "test_a_delete_of_an_older_or_equal_document_applies, DELETE, 6, 1, 7, APPLIED",
            "test_a_delete_of_an_older_or_equal_document_applies, DELETE, 7, 1, 7, APPLIED",
            "test_a_version_of_zero_applies_unconditionally, SAVE, 99, 99, 0, APPLIED",
            "test_a_version_of_zero_applies_unconditionally, DELETE, 99, 1, 0, APPLIED"})
    public void test_the_apply_outcome_truth_table(String scenario, String op, long stored, long tombstoneOrExists,
            long version, ApplyOutcome expected) {
        final var outcome = "SAVE".equals(op)
                ? ApplyOutcome.forSave(stored, tombstoneOrExists, version)
                : ApplyOutcome.forDelete(stored, tombstoneOrExists == 1L, version);

        assertEquals(expected, outcome, scenario);
    }

    @Test
    public void test_only_a_superseded_op_withholds_its_triggers() {
        assertTrue(ApplyOutcome.APPLIED.firesTriggers());
        assertTrue(ApplyOutcome.ALREADY_APPLIED.firesTriggers());
        assertFalse(ApplyOutcome.SUPERSEDED.firesTriggers());
    }
}
