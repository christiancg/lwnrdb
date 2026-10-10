package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminEntryLandedTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);

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

    private static MockedStatic<AdminPageHelper> pageRowFailingWith(String collName, Exception failure) {
        final var mocked = mockStatic(AdminPageHelper.class, CALLS_REAL_METHODS);
        mocked.when(() -> AdminPageHelper.baseUpdateEntryCount(eq(Globals.ADMIN_DB_NAME), eq(collName), any(),
                anyList(), anyBoolean())).thenThrow(failure);
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

    private CollectionUsageEvent usageEvent(String indexKey) {
        memoryManagement.recordAccess(AccessKind.FIELD_INDEX, TestGlobals.DB, TestGlobals.COLL, indexKey);
        return new CollectionUsageEvent(AccessKind.FIELD_INDEX, TestGlobals.DB, TestGlobals.COLL, indexKey,
                System.currentTimeMillis());
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
            assertDoesNotThrow(() -> AdminOperationHelper.upsertCollectionUsage(event));
        }
        assertNotNull(cache.getPkIndexCollectionUsage(usageId("landedNewUsage")));

        AdminOperationHelper.upsertCollectionUsage(usageEvent("landedNewUsage"));

        assertEquals(1, usageRowsOnDisk(usageId("landedNewUsage")),
                "a usage record left unpublished is inserted a second time under the same _id");
    }

    @Test
    public void test_an_updated_usage_record_is_published_with_its_new_position() throws Exception {
        AdminOperationHelper.upsertCollectionUsage(usageEvent("landedUpdatedUsage"));
        final var event = usageEvent("landedUpdatedUsage");
        try (var ignored = usagePageRowFailing()) {
            assertDoesNotThrow(() -> AdminOperationHelper.upsertCollectionUsage(event));
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
        AdminOperationHelper.upsertCollectionUsage(usageEvent("expiredUsageOne"));
        AdminOperationHelper.upsertCollectionUsage(usageEvent("expiredUsageTwo"));
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
            AdminOperationHelper.upsertCollectionUsage(event);
        }

        assertTrue(Thread.interrupted(), "the interrupt must survive the swallowed bookkeeping failure");
        assertNotNull(cache.getPkIndexCollectionUsage(usageId("interruptedUsage")));
    }
}
