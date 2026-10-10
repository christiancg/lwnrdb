package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.DurableReplayIncompleteException;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.tx.TransactionRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class VersionedPreparedReplayTest extends VersionedReplaySupport {
    private static final String LATE_COLL = "late-coll";

    private void commitPrepared(String dtxId) throws Exception {
        TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 0L);
    }

    @Test
    public void test_a_prepared_replay_finishes_its_own_prefix() throws Exception {
        clustered(true);
        final var dtxId = UUID.randomUUID().toString();
        final var first = preparedSave(dtxId, 0, document("prep", "first"));
        preparedSave(dtxId, 1, document("prep", "second"));
        markPrepared(dtxId);
        applyPrefix(List.of(first.get_id()), 1);

        commitPrepared(dtxId);

        assertEquals("second", valueOf("prep"));
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }

    @Test
    public void test_a_prepared_replay_leaves_a_write_made_after_the_prepare() throws Exception {
        clustered(true);
        final var dtxId = UUID.randomUUID().toString();
        preparedSave(dtxId, 0, document("foreign", "mine"));
        markPrepared(dtxId);
        foreignWrite("foreign", "theirs");

        commitPrepared(dtxId);

        assertEquals("theirs", valueOf("foreign"));
    }

    @Test
    public void test_a_prepared_replay_leaves_a_foreign_write_to_a_multi_op_id() throws Exception {
        clustered(true);
        final var dtxId = UUID.randomUUID().toString();
        preparedSave(dtxId, 0, document("prepForeign", "first"));
        preparedSave(dtxId, 1, document("prepForeign", "second"));
        markPrepared(dtxId);
        foreignWrite("prepForeign", "theirs");

        commitPrepared(dtxId);

        assertEquals("theirs", valueOf("prepForeign"));
    }

    @Test
    public void test_a_foreign_delete_after_the_prepare_is_not_undone() throws Exception {
        clustered(true);
        final var dtxId = UUID.randomUUID().toString();
        preparedSave(dtxId, 0, document("deletedLater", "mine"));
        markPrepared(dtxId);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "deletedLater", clock.next());

        commitPrepared(dtxId);

        assertNull(valueOf("deletedLater"));
    }

    @Test
    public void test_an_id_deleted_before_the_prepare_is_recreated() throws Exception {
        clustered(true);
        final var dtxId = UUID.randomUUID().toString();
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "deletedEarlier", clock.next());
        preparedSave(dtxId, 0, document("deletedEarlier", "mine"));
        markPrepared(dtxId);

        commitPrepared(dtxId);

        assertEquals("mine", valueOf("deletedEarlier"));
    }

    @Test
    public void test_an_id_the_slice_deleted_itself_before_saving_ends_at_the_save() throws Exception {
        clustered(true);
        foreignWrite("ownDelete", "before");
        final var dtxId = UUID.randomUUID().toString();
        final var deletedId = new JsonObject();
        deletedId.add(Globals.PK_FIELD, new JsonString("ownDelete"));
        final var delete = preparedOp(dtxId, 0, AdminTransactionEntry.OP_TYPE_DELETE, deletedId);
        preparedSave(dtxId, 1, document("ownDelete", "mine"));
        markPrepared(dtxId);
        applyPrefix(List.of(delete.get_id()), 1);
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "ownDelete", delete.versionAt(0));

        commitPrepared(dtxId);

        assertEquals("mine", valueOf("ownDelete"));
    }

    @Test
    public void test_a_replay_that_cannot_read_its_tombstones_applies_nothing_and_stays_prepared() throws Exception {
        clustered(true);
        final var dtxId = UUID.randomUUID().toString();
        preparedSave(dtxId, 0, document("unreadableTombstones", "mine"));
        markPrepared(dtxId);
        final var tombstones = new File(TestUtils.getDbPath(fs), TestGlobals.DB + File.separator + TestGlobals.COLL
                + File.separator + TestGlobals.COLL + "-tombstones.idx");
        Files.deleteIfExists(tombstones.toPath());
        assertTrue(tombstones.mkdirs(), "a directory in place of the tombstone file makes its read fail");
        try {
            assertThrows(Exception.class, () -> commitPrepared(dtxId));

            assertNull(valueOf("unreadableTombstones"));
            assertTrue(org.techhouse.ops.TxCommitLog.isLocallyCommitted(dtxId), "the slice stays decided for a retry");
        } finally {
            assertTrue(tombstones.delete());
        }
    }

    @Test
    public void test_a_failed_then_successful_replay_leaves_no_write_lock_held() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        preparedSave(dtxId, 0, document("retried", "v"));
        final var late = new AdminTransactionEntry(dtxId, "client", 1, AdminTransactionEntry.OP_TYPE_SAVE,
                TestGlobals.DB, LATE_COLL, document("late", "v"));
        AdminOperationHelper.saveTransactionOp(late);
        markPrepared(dtxId);

        assertThrows(DurableReplayIncompleteException.class,
                () -> TransactionRecovery.commitPreparedFromDurable(dtxId, List.of(collId), 1000L));
        assertTrue(locks.isWriteLockedByCurrentThread(collId), "the failed attempt keeps its lock by design");

        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, LATE_COLL));
        fs.createCollectionFile(TestGlobals.DB, LATE_COLL);
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
