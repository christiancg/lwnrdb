package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cache.UsageCounter;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminEntryLandedTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void resetClients() throws Exception {
        TestUtils.resetClients();
    }

    private static MockedStatic<AdminPageHelper> pageRowFailingWith(String collName, Exception failure) {
        final var mocked = mockStatic(AdminPageHelper.class, CALLS_REAL_METHODS);
        mocked.when(() -> AdminPageHelper.baseUpdateEntryCount(eq(Globals.ADMIN_DB_NAME), eq(collName), any(),
                anyList(), anyBoolean())).thenThrow(failure);
        mocked.when(() -> AdminPageHelper.baseUpdateEntryCountOfRefs(eq(Globals.ADMIN_DB_NAME), eq(collName), any(),
                anyList())).thenThrow(failure);
        return mocked;
    }

    private static MockedStatic<AdminPageHelper> transactionPageRowFailing() {
        return pageRowFailingWith(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, new IOException("disk full"));
    }

    private static AdminTransactionEntry marker(String txId, String note) {
        final var payload = new JsonObject();
        payload.addProperty("note", note);
        return AdminTransactionEntry.marker(txId, AdminTransactionEntry.MARKER_LOCAL_COMMIT,
                AdminTransactionEntry.OP_TYPE_LOCAL_COMMIT, payload);
    }

    private static String notePayloadOf(String recordId) throws Exception {
        return AdminOperationHelper.readTransactionOps(List.of(recordId)).getFirst().getPayload().get("note")
                .asJsonString().getValue();
    }

    @Test
    public void test_a_new_transaction_record_is_published_when_its_page_row_fails() throws Exception {
        final var record = marker("landed-new", "first");
        try (var ignored = transactionPageRowFailing()) {
            assertDoesNotThrow(() -> AdminOperationHelper.saveTransactionOp(record));
        }

        assertNotNull(cache.getPkIndexTransaction(record.get_id()));
        assertEquals("first", notePayloadOf(record.get_id()));
    }

    @Test
    public void test_an_updated_transaction_record_is_published_with_its_new_length() throws Exception {
        AdminOperationHelper.saveTransactionOp(marker("landed-update", "short"));
        final var grown = marker("landed-update", "a considerably longer payload than the first one");
        try (var ignored = transactionPageRowFailing()) {
            AdminOperationHelper.saveTransactionOp(grown);
        }

        assertEquals(grown.byteSize(), cache.getPkIndexTransaction(grown.get_id()).getLength());
        assertEquals("a considerably longer payload than the first one", notePayloadOf(grown.get_id()));
    }

    @Test
    public void test_an_interrupted_page_row_restores_the_interrupt_and_publishes() throws Exception {
        final var record = marker("landed-interrupted", "interrupted");
        try (var ignored = pageRowFailingWith(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME,
                new InterruptedException("stop"))) {
            AdminOperationHelper.saveTransactionOp(record);
        }

        assertTrue(Thread.interrupted(), "the interrupt must survive the swallowed bookkeeping failure");
        assertNotNull(cache.getPkIndexTransaction(record.get_id()));
    }

    @Test
    public void test_a_deleted_transaction_record_leaves_the_cache_when_its_page_row_fails() throws Exception {
        final var record = marker("landed-delete", "doomed");
        AdminOperationHelper.saveTransactionOp(record);
        try (var ignored = transactionPageRowFailing()) {
            assertDoesNotThrow(() -> AdminOperationHelper.deleteTransactionOps(List.of(record.get_id())));
        }

        assertNull(cache.getPkIndexTransaction(record.get_id()));
        assertTrue(AdminOperationHelper.readTransactionOps(List.of(record.get_id())).isEmpty());
    }

    @Test
    public void test_a_user_entry_is_published_when_its_page_row_fails() {
        final var user = new AdminUserEntry("landeduser", "hash", false, Set.of(), Map.of(), Map.of());
        try (var ignored = pageRowFailingWith(Globals.ADMIN_USERS_COLLECTION_NAME, new IOException("disk full"))) {
            assertDoesNotThrow(() -> AdminOperationHelper.saveUserEntry(user));
        }

        assertNotNull(cache.getAdminUserEntry("landeduser"));
        assertNotNull(cache.getPkIndexAdminUserEntry("landeduser"));
    }

    private static MockedStatic<AdminPageHelper> usagePageRowFailing() {
        return pageRowFailingWith(Globals.ADMIN_COLLECTION_USAGE_NAME, new IOException("disk full"));
    }

    private List<UsageCounter> usageEvent(String indexKey) {
        memoryManagement.recordAccess(AccessKind.FIELD_INDEX, TestGlobals.DB, TestGlobals.COLL, indexKey);
        return List.of(memoryManagement.getCounter(AccessKind.FIELD_INDEX, TestGlobals.DB, TestGlobals.COLL, indexKey));
    }

    private static String usageId(String indexKey) {
        return AdminCollectionUsageEntry.buildId(TestGlobals.DB, TestGlobals.COLL, indexKey);
    }

    private long usageRowsOnDisk(String id) throws IOException {
        return fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME).stream()
                .filter(pk -> pk.getValue().equals(id)).count();
    }

    @Test
    public void test_a_new_usage_record_is_published_when_its_page_row_fails() throws Exception {
        final var event = usageEvent("landedNewUsage");
        try (var ignored = usagePageRowFailing()) {
            assertDoesNotThrow(() -> AdminOperationHelper.flushCollectionUsage(event));
        }
        assertNotNull(cache.getPkIndexCollectionUsage(usageId("landedNewUsage")));

        AdminOperationHelper.flushCollectionUsage(usageEvent("landedNewUsage"));

        assertEquals(1, usageRowsOnDisk(usageId("landedNewUsage")),
                "a usage record left unpublished is inserted a second time under the same _id");
    }

    @Test
    public void test_an_updated_usage_record_is_published_with_its_new_position() throws Exception {
        AdminOperationHelper.flushCollectionUsage(usageEvent("landedUpdatedUsage"));
        final var event = usageEvent("landedUpdatedUsage");
        try (var ignored = usagePageRowFailing()) {
            assertDoesNotThrow(() -> AdminOperationHelper.flushCollectionUsage(event));
        }

        final var onDisk = fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME).stream()
                .filter(pk -> pk.getValue().equals(usageId("landedUpdatedUsage"))).findFirst().orElseThrow();
        final var cached = cache.getPkIndexCollectionUsage(usageId("landedUpdatedUsage"));
        assertEquals(onDisk.getPosition(), cached.getPosition());
        assertEquals(onDisk.getLength(), cached.getLength());
        assertDoesNotThrow(() -> AdminOperationHelper.cleanupCollectionUsage(Long.MAX_VALUE));
    }

    @Test
    public void test_a_usage_cleanup_continues_past_a_failing_page_row() throws Exception {
        AdminOperationHelper.flushCollectionUsage(usageEvent("expiredUsageOne"));
        AdminOperationHelper.flushCollectionUsage(usageEvent("expiredUsageTwo"));
        try (var ignored = usagePageRowFailing()) {
            assertDoesNotThrow(() -> AdminOperationHelper.cleanupCollectionUsage(-60_000L));
        }

        for (final var indexKey : List.of("expiredUsageOne", "expiredUsageTwo")) {
            assertNull(cache.getPkIndexCollectionUsage(usageId(indexKey)));
            assertEquals(0, usageRowsOnDisk(usageId(indexKey)));
        }
    }

    @Test
    public void test_an_interrupted_usage_page_row_restores_the_interrupt() throws Exception {
        final var event = usageEvent("interruptedUsage");
        try (var ignored = pageRowFailingWith(Globals.ADMIN_COLLECTION_USAGE_NAME, new InterruptedException("stop"))) {
            AdminOperationHelper.flushCollectionUsage(event);
        }

        assertTrue(Thread.interrupted(), "the interrupt must survive the swallowed bookkeeping failure");
        assertNotNull(cache.getPkIndexCollectionUsage(usageId("interruptedUsage")));
    }

    private UUID transactionSaving(String clientName, String id) {
        final var clientId = clientTracker.registerForwardedClient(clientName);
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        request.setObject(object);
        request.set_id(id);
        TransactionOperationHelper.bufferSave(request, clientTracker.getActiveTransaction(clientId));
        return clientId;
    }

    private boolean isVisible(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request) instanceof FindByIdResponse found && found.getObject() != null;
    }

    @Test
    public void test_a_commit_whose_marker_page_row_fails_still_commits() {
        final var clientId = transactionSaving("marker-page-row", "page-row-failed");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var txId = transaction.getTransactionId().toString();
        final var opIds = new ArrayList<>(transaction.getBufferedOpIds());

        final OperationStatus status;
        try (var ignored = transactionPageRowFailing()) {
            status = TransactionOperationHelper.commit(clientId).getStatus();
        }

        assertEquals(OperationStatus.OK, status, "a marker that landed must not be reported as a failed commit");
        assertFalse(TxCommitLog.localCommitTxIds().contains(txId), "a finished commit leaves nothing to replay");
        opIds.forEach(opId -> assertNull(cache.getPkIndexTransaction(opId)));
        assertTrue(isVisible("page-row-failed"));
    }

    @Test
    public void test_a_commit_whose_marker_never_landed_leaves_nothing_to_replay() {
        final var clientId = transactionSaving("marker-not-landed", "marker-not-landed");
        final var txId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();

        final String errorCode;
        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if ("writeTransactionRecords".equals(invocation.getMethod().getName())
                    && invocation.<List<AdminTransactionEntry>>getArgument(0).stream()
                            .anyMatch(entry -> entry.get_id().equals(TxCommitLog.markerId(txId)))) {
                throw new IOException("disk full");
            }
            return invocation.callRealMethod();
        })) {
            errorCode = TransactionOperationHelper.commit(clientId).getErrorCode();
        }

        assertEquals(ErrorCode.ERROR_TRANSACTION.getCode(), errorCode);
        assertFalse(TxCommitLog.localCommitTxIds().contains(txId));
        assertNull(clientTracker.getActiveTransaction(clientId), "a commit that never reached its marker deregisters");
        assertFalse(isVisible("marker-not-landed"));
    }
}
