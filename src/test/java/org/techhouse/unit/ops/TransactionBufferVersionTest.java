package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionBufferVersionTest extends VersionedReplaySupport {

    private List<AdminTransactionEntry> bufferedOps(Transaction transaction) throws Exception {
        final var ops = new ArrayList<>(AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds()));
        ops.sort(Comparator.comparingLong(AdminTransactionEntry::getSeq));
        return ops;
    }

    @Test
    public void test_each_buffered_op_gets_a_strictly_increasing_version() throws Exception {
        final var transaction = newTransaction();
        bufferSave(transaction, "a", "one");
        bufferSave(transaction, "a", "two");
        bufferDelete(transaction, "a");

        final var ops = bufferedOps(transaction);

        assertEquals(3, ops.size());
        var previous = 0L;
        for (final var op : ops) {
            assertEquals(1, op.getVersions().size());
            assertTrue(op.versionAt(0) > previous, "versions must order the slice's own ops on one id");
            previous = op.versionAt(0);
        }
        assertEquals(previous, transaction.versionOf(collId, "a"), "the overlay keeps the last op's version");
    }

    @Test
    public void test_a_bulk_save_gets_one_version_per_element() throws Exception {
        final var transaction = newTransaction();
        bufferBulkSave(transaction, document("b1", "x"), document("b2", "y"), document("b3", "z"));

        final var op = bufferedOps(transaction).getFirst();

        assertEquals(3, op.getVersions().size());
        assertNotEquals(op.versionAt(0), op.versionAt(1));
        assertEquals(op.versionAt(2), transaction.versionOf(collId, "b3"));
    }

    @Test
    public void test_a_write_refused_by_its_checks_buffers_no_op() throws Exception {
        final var config = Configuration.getInstance();
        final var original = config.getMaxEntrySize();
        TestUtils.setPrivateField(config, "maxEntrySize", 8L);
        try {
            final var transaction = newTransaction();
            final var response = TransactionOperationHelper.bufferSave(saveRequest(document("big", "too large")),
                    transaction);

            assertNotEquals(OperationStatus.OK, response.getStatus());
            assertTrue(transaction.getBufferedOpIds().isEmpty());
            assertEquals(0L, transaction.versionOf(collId, "big"));
        } finally {
            TestUtils.setPrivateField(config, "maxEntrySize", original);
        }
    }

    @Test
    public void test_the_versions_survive_a_round_trip_through_the_admin_record() {
        final var op = new AdminTransactionEntry("tx", "client", 4, AdminTransactionEntry.OP_TYPE_BULK_SAVE,
                TestGlobals.DB, TestGlobals.COLL, document("r", "v"));
        op.setVersions(List.of(Long.MAX_VALUE - 1, 42L));

        final var stored = op.getData().deepCopy();
        stored.addProperty(org.techhouse.config.Globals.PK_FIELD, op.get_id());
        final var read = AdminTransactionEntry.fromJsonObject(stored);

        assertEquals(List.of(Long.MAX_VALUE - 1, 42L), read.getVersions());
        assertEquals(0L, read.versionAt(2), "a missing position reads as unversioned");
    }
}
