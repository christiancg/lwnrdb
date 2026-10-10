package org.techhouse.unit.ops;

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
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.tx.SliceCommit;
import org.techhouse.test.TestGlobals;

public class SliceCommitTest extends VersionedReplaySupport {

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
}
