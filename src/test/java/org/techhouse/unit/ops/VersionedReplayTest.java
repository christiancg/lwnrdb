package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.test.TestGlobals;

public class VersionedReplayTest extends VersionedReplaySupport {

    @Test
    public void test_a_replay_after_a_partial_apply_ends_at_the_last_op() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "twice", "first");
        bufferSave(transaction, "twice", "second");
        final var txId = commitMarkerFor(transaction);
        applyPrefix(transaction.getBufferedOpIds(), 1);

        crashAndRestart();

        assertEquals("second", valueOf("twice"));
        assertFalse(TxCommitLog.isLocallyCommitted(txId));
    }

    @Test
    public void test_save_save_on_one_id_replays_to_the_second_value_clustered() throws Exception {
        clustered(true);
        test_a_replay_after_a_partial_apply_ends_at_the_last_op();
    }

    @Test
    public void test_applies_only_what_is_missing_when_the_second_of_three_saves_landed() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "thrice", "one");
        bufferSave(transaction, "thrice", "two");
        bufferSave(transaction, "thrice", "three");
        commitMarkerFor(transaction);
        applyPrefix(transaction.getBufferedOpIds(), 2);

        crashAndRestart();

        assertEquals("three", valueOf("thrice"));
    }

    @Test
    public void test_an_id_that_returns_to_an_earlier_value_ends_at_the_last_op() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "flip", "a");
        bufferSave(transaction, "flip", "b");
        bufferSave(transaction, "flip", "a");
        commitMarkerFor(transaction);
        applyPrefix(transaction.getBufferedOpIds(), 2);

        crashAndRestart();

        assertEquals("a", valueOf("flip"));
    }

    @Test
    public void test_save_then_delete_on_one_id_replays_to_absent() throws Exception {
        foreignWrite("doomed", "original");
        final var transaction = newTransaction();
        bufferSave(transaction, "doomed", "updated");
        bufferDelete(transaction, "doomed");
        commitMarkerFor(transaction);
        applyPrefix(transaction.getBufferedOpIds(), 1);

        crashAndRestart();

        assertNull(valueOf("doomed"));
    }

    @Test
    public void test_save_then_delete_on_one_id_replays_to_absent_clustered() throws Exception {
        clustered(true);
        test_save_then_delete_on_one_id_replays_to_absent();
    }

    @Test
    public void test_delete_then_save_on_one_id_replays_to_the_save() throws Exception {
        foreignWrite("reborn", "original");
        final var transaction = newTransaction();
        bufferDelete(transaction, "reborn");
        bufferSave(transaction, "reborn", "again");
        commitMarkerFor(transaction);
        applyPrefix(transaction.getBufferedOpIds(), 1);

        crashAndRestart();

        assertEquals("again", valueOf("reborn"));
    }

    @Test
    public void test_delete_then_save_on_one_id_replays_to_the_save_clustered() throws Exception {
        clustered(true);
        test_delete_then_save_on_one_id_replays_to_the_save();
    }

    @Test
    public void test_every_op_replays_when_the_crash_came_before_the_first_apply() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "gone", "one");
        bufferSave(transaction, "gone", "two");
        bufferSave(transaction, "other", "three");
        final var txId = commitMarkerFor(transaction);

        crashAndRestart();

        assertEquals("two", valueOf("gone"));
        assertEquals("three", valueOf("other"));
        assertTrue(cache.getTransactionPkIndexes().keySet().stream().noneMatch(id -> id.startsWith(txId)),
                "no slice or marker may survive the sweep");
    }

    @Test
    public void test_a_bulk_save_then_a_save_of_an_id_inside_it_finishes() throws Exception {
        final var transaction = newTransaction();
        bufferBulkSave(transaction, document("bulkA", "one"), document("bulkB", "one"));
        bufferSave(transaction, "bulkA", "two");
        commitMarkerFor(transaction);
        applyPrefix(transaction.getBufferedOpIds(), 1);

        crashAndRestart();

        assertEquals("two", valueOf("bulkA"));
        assertEquals("one", valueOf("bulkB"));
    }

    @Test
    public void test_a_foreign_write_to_an_id_touched_once_is_left_alone() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "solo", "mine");
        commitMarkerFor(transaction);
        foreignWrite("solo", "theirs");

        crashAndRestart();

        assertEquals("theirs", valueOf("solo"));
    }

    @Test
    public void test_a_foreign_write_to_an_id_touched_twice_is_left_alone() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "contested", "one");
        bufferSave(transaction, "contested", "two");
        commitMarkerFor(transaction);
        foreignWrite("contested", "theirs");

        crashAndRestart();

        assertEquals("theirs", valueOf("contested"));
    }

    @Test
    public void test_standalone_and_clustered_replays_leave_a_foreign_write_alone_alike() throws Exception {
        clustered(true);
        test_a_foreign_write_to_an_id_touched_twice_is_left_alone();
    }

    @Test
    public void test_an_unversioned_op_from_an_older_build_still_applies() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        final var op = new AdminTransactionEntry(dtxId, "client", 0, AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB,
                TestGlobals.COLL, document("legacy", "old-build"));
        AdminOperationHelper.saveTransactionOp(op);
        foreignWrite("legacy", "stored");
        TxCommitLog.recordLocalCommit(dtxId, List.of(op.get_id()), List.of(collId));

        crashAndRestart();

        assertEquals("old-build", valueOf("legacy"));
    }
}
