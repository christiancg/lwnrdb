package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.DurableReplayIncompleteException;
import org.techhouse.ex.LoggedWriteNotAppliedException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.admin.TransactionRecords;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.TransactionRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TransactionRecordCalls;

public class TransactionRecoveryCompletenessTest {
    private static final int OP_PAYLOAD_CHARS = 700_000;
    private static final int LARGE_OPS = 12;
    private static final int LAST_RECORD = 3;
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final String collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        useCache(cache);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void useCache(Cache target) throws Exception {
        TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", target);
        TestUtils.setPrivateStaticField(Class.forName("org.techhouse.ops.admin.AdminBatchWrite"), "cache", target);
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private static Transaction bufferSlice(String... ids) throws Exception {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        for (final var id : ids) {
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObject(document(id));
            request.set_id(id);
            TransactionOperationHelper.bufferSave(request, transaction);
        }
        TransactionRecordCalls.persistBufferedOps(transaction);
        return transaction;
    }

    private boolean documentExists(String id) throws Exception {
        return !cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of(id)).isEmpty();
    }

    private static List<AdminTransactionEntry> largeOps(String txId) {
        final var ops = new ArrayList<AdminTransactionEntry>();
        final var filler = "x".repeat(OP_PAYLOAD_CHARS);
        for (var seq = 0; seq < LARGE_OPS; seq++) {
            final var payload = new JsonObject();
            payload.addProperty(Globals.PK_FIELD, "large-" + seq);
            payload.addProperty("filler", filler);
            ops.add(new AdminTransactionEntry(txId, "client", seq, AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB,
                    TestGlobals.COLL, payload));
        }
        return ops;
    }

    @Test
    public void aLastRecordLoggedButNotAppliedKeepsTheChunksBeforeIt() throws Exception {
        final var txId = UUID.randomUUID().toString();
        final var ops = largeOps(txId);
        final var inserts = new ArrayList<>(ops);
        inserts.add(TxCommitLog.localCommitMarker(txId, ops.stream().map(AdminTransactionEntry::get_id).toList(),
                List.of(collId)));
        final var records = new AtomicInteger();
        final var spy = Mockito.spy(cache);
        Mockito.doAnswer(invocation -> {
            if (records.incrementAndGet() == LAST_RECORD) {
                throw new UncheckedIOException(new LoggedWriteNotAppliedException("admin|transactions",
                        new IOException("No space left on device")));
            }
            return invocation.callRealMethod();
        }).when(spy).selectPagesForInsert(ArgumentMatchers.eq(Globals.ADMIN_DB_NAME),
                ArgumentMatchers.eq(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME), ArgumentMatchers.any());
        useCache(spy);
        try {
            assertThrows(UncheckedIOException.class,
                    () -> AdminOperationHelper.writeTransactionRecords(inserts, List.of()));
        } finally {
            useCache(cache);
        }

        assertEquals(10, ops.stream().map(AdminTransactionEntry::get_id)
                .filter(cache.getTransactionPkIndexes()::containsKey).count(),
                "the chunks before a last record the log may still apply are not taken back");
        AdminOperationHelper.deleteTransactionOps(new ArrayList<>(cache.getTransactionPkIndexes().keySet()));
    }

    @Test
    public void aLocalCommitMissingAnOpIsNotReplayed() throws Exception {
        final var transaction = bufferSlice("first", "second", "third");
        final var txId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        TxCommitLog.recordLocalCommit(txId, opIds, List.of(collId));
        AdminOperationHelper.deleteTransactionOps(List.of(opIds.getFirst()));
        TestUtils.releaseAllLocks();

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertFalse(documentExists("second"), "an incomplete slice applies nothing");
        assertFalse(documentExists("third"), "an incomplete slice applies nothing");
        assertTrue(TxCommitLog.isLocallyCommitted(txId), "its records stay for the operator");
    }

    @Test
    public void aRefusedCommittingSliceKeepsItsCollectionsLockedAndFenced() throws Exception {
        final var transaction = bufferSlice("k-first", "k-second");
        final var txId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        TxCommitLog.recordLocalCommit(txId, opIds, List.of(collId));
        AdminOperationHelper.deleteTransactionOps(List.of(opIds.getFirst()));
        TestUtils.releaseAllLocks();

        assertFalse(TransactionRecovery.finishLocalCommit(txId));

        assertTrue(locks.isWriteLockedByCurrentThread(collId), "a slice that may be partly applied keeps its fence");
        assertTrue(locks.fences(List.of(collId)), "so a read answers 503-18 instead of waiting");
    }

    @Test
    public void aPreparedSliceMissingAnOpIsNotCommitted() throws Exception {
        final var transaction = bufferSlice("p-first", "p-second");
        final var txId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        Tx2pcLog.recordParticipantPrepared(txId, "coordinator:1", List.of(), List.of(collId), List.of(), opIds);
        AdminOperationHelper.deleteTransactionOps(List.of(opIds.getFirst()));
        TestUtils.releaseAllLocks();

        assertThrows(DurableReplayIncompleteException.class,
                () -> TransactionRecovery.resolveFromDurable(txId, true, 1000L));

        assertFalse(documentExists("p-second"), "an incomplete prepared slice applies nothing");
    }
}
