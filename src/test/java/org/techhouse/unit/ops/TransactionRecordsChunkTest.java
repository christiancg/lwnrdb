package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.admin.TransactionRecords;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionRecordsChunkTest {
    private static final int OP_PAYLOAD_CHARS = 700_000;
    private static final int OPS = 12;
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", cache);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static List<AdminTransactionEntry> largeOps(String txId) {
        final var ops = new ArrayList<AdminTransactionEntry>();
        final var filler = "x".repeat(OP_PAYLOAD_CHARS);
        for (var seq = 0; seq < OPS; seq++) {
            final var payload = new JsonObject();
            payload.addProperty(Globals.PK_FIELD, "large-" + seq);
            payload.addProperty("filler", filler);
            ops.add(new AdminTransactionEntry(txId, "client", seq, AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB,
                    TestGlobals.COLL, payload));
        }
        return ops;
    }

    private List<Integer> recordSizesWhile(Runnable write) throws Exception {
        final var records = new ArrayList<Integer>();
        final var spy = Mockito.spy(cache);
        Mockito.doAnswer(invocation -> {
            records.add(invocation.<int[]>getArgument(2).length);
            return invocation.callRealMethod();
        }).when(spy).selectPagesForInsert(ArgumentMatchers.eq(Globals.ADMIN_DB_NAME),
                ArgumentMatchers.eq(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME), ArgumentMatchers.any());
        TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", spy);
        try {
            write.run();
        } finally {
            TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", cache);
        }
        return records;
    }

    @Test
    public void test_ops_above_the_record_budget_go_out_in_chunks_with_the_marker_riding_the_last() throws Exception {
        final var txId = UUID.randomUUID().toString();
        final var ops = largeOps(txId);
        final var marker = TxCommitLog.localCommitMarker(txId, ops.stream().map(AdminTransactionEntry::get_id).toList(),
                List.of(TestGlobals.DB + Globals.COLL_IDENTIFIER_SEPARATOR + TestGlobals.COLL));
        final var inserts = new ArrayList<AdminTransactionEntry>();
        inserts.add(marker);
        inserts.addAll(ops);

        final var records = recordSizesWhile(() -> {
            try {
                AdminOperationHelper.writeTransactionRecords(inserts, List.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(List.of(5, 5, 3), records, "5 ops fit a 4 MB record; the marker rides the last one");
        assertTrue(TxCommitLog.isLocallyCommitted(txId));
        assertEquals(OPS, AdminOperationHelper
                .readTransactionOps(ops.stream().map(AdminTransactionEntry::get_id).toList()).size());
        AdminOperationHelper.deleteTransactionOps(new ArrayList<>(cache.getTransactionPkIndexes().keySet()));
    }

    @Test
    public void test_a_failed_last_record_takes_back_the_chunks_written_before_it() throws Exception {
        final var txId = UUID.randomUUID().toString();
        final var ops = largeOps(txId);
        final var inserts = new ArrayList<>(ops);
        inserts.add(TxCommitLog.localCommitMarker(txId, List.of(), List.of()));
        final var records = new java.util.concurrent.atomic.AtomicInteger();
        final var spy = Mockito.spy(cache);
        Mockito.doAnswer(invocation -> {
            if (records.incrementAndGet() == 3) {
                throw new IllegalStateException("the last record cannot be placed");
            }
            return invocation.callRealMethod();
        }).when(spy).selectPagesForInsert(ArgumentMatchers.eq(Globals.ADMIN_DB_NAME),
                ArgumentMatchers.eq(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME), ArgumentMatchers.any());
        TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", spy);
        try {
            assertThrows(IllegalStateException.class,
                    () -> AdminOperationHelper.writeTransactionRecords(inserts, List.of()));
        } finally {
            TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", cache);
        }

        assertFalse(TxCommitLog.isLocallyCommitted(txId));
        assertFalse(cache.getTransactionPkIndexes().keySet().stream().anyMatch(id -> id.startsWith(txId)),
                "the chunks that landed before the failed record are taken back");
    }

    @Test
    public void test_chunks_written_before_a_crash_are_orphans_the_startup_sweep_removes() throws Exception {
        final var txId = UUID.randomUUID().toString();
        final var ops = largeOps(txId);
        AdminOperationHelper.writeTransactionRecords(ops.subList(0, 5), List.of());
        assertTrue(cache.getTransactionPkIndexes().keySet().stream().anyMatch(id -> id.startsWith(txId)));

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertFalse(cache.getTransactionPkIndexes().keySet().stream().anyMatch(id -> id.startsWith(txId)),
                "a chunk whose marker never landed belongs to no decided transaction");
    }

    @Test
    public void test_a_small_slice_is_one_record() throws Exception {
        final var txId = UUID.randomUUID().toString();
        final var payload = new JsonObject();
        payload.addProperty(Globals.PK_FIELD, "small");
        final var op = new AdminTransactionEntry(txId, "client", 0, AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB,
                TestGlobals.COLL, payload);
        final var marker = TxCommitLog.localCommitMarker(txId, List.of(op.get_id()), List.of());

        final var records = recordSizesWhile(() -> {
            try {
                AdminOperationHelper.writeTransactionRecords(List.of(op, marker), List.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(List.of(2), records);
        AdminOperationHelper.deleteTransactionOps(List.of(op.get_id(), marker.get_id()));
    }
}
