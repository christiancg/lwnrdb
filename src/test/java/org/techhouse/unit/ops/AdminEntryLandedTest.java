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
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.test.TestUtils;

public class AdminEntryLandedTest {
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
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
}
