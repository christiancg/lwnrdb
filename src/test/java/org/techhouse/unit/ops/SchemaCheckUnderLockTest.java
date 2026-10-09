package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SchemaCheckUnderLockTest {
    private static final String STRICT_SCHEMA = "{\"type\":\"object\",\"required\":[\"name\"],"
            + "\"properties\":{\"name\":{\"type\":\"string\"}}}";
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
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
    void reset() throws Exception {
        TestUtils.releaseAllLocks();
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        fs.deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
    }

    private void schemaSavedAfterTheEdgeCheck() {
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, eJson.fromJson(STRICT_SCHEMA, JsonObject.class));
    }

    private static JsonObject document(String id, String name) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        if (name != null) {
            object.add("name", new JsonString(name));
        }
        return object;
    }

    private static SaveRequest save(JsonObject document) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document);
        request.set_id(document.get(Globals.PK_FIELD).asJsonString().getValue());
        return request;
    }

    private static BulkSaveRequest bulkSave(JsonObject... documents) {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(documents));
        return request;
    }

    private boolean stored(String id) throws Exception {
        return !cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of(id)).isEmpty();
    }

    private static void assertRefusedBySchema(OperationResponse response) {
        assertEquals(ErrorCode.SCHEMA_VALIDATION_FAILED.getCode(), response.getErrorCode(), response.getMessage());
    }

    @Test
    public void test_a_save_validated_before_the_schema_changed_is_refused_under_the_lock() throws Exception {
        schemaSavedAfterTheEdgeCheck();

        assertRefusedBySchema(processor.processMessage(save(document("lateSave", null))));
        assertFalse(stored("lateSave"), "a refused write leaves nothing behind");
    }

    @Test
    public void test_a_bulk_save_validated_before_the_schema_changed_is_refused_under_the_lock() throws Exception {
        schemaSavedAfterTheEdgeCheck();

        assertRefusedBySchema(
                processor.processMessage(bulkSave(document("lateBulkA", "ok"), document("lateBulkB", null))));
        assertFalse(stored("lateBulkA") || stored("lateBulkB"), "a bulk save is all or nothing");
    }

    @Test
    public void test_a_transactional_save_is_checked_after_it_takes_the_lock() {
        schemaSavedAfterTheEdgeCheck();
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());

        assertRefusedBySchema(TransactionOperationHelper.bufferSave(save(document("lateTx", null)), transaction));
        assertTrue(transaction.getBufferedOpIds().isEmpty(), "nothing is buffered for the commit to apply");
    }

    @Test
    public void test_a_transactional_bulk_save_is_checked_after_it_takes_the_lock() {
        schemaSavedAfterTheEdgeCheck();
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());

        assertRefusedBySchema(TransactionOperationHelper
                .bufferBulkSave(bulkSave(document("lateTxA", "ok"), document("lateTxB", null)), transaction));
        assertTrue(transaction.getBufferedOpIds().isEmpty());
    }

    @Test
    public void test_a_compliant_save_still_lands() throws Exception {
        schemaSavedAfterTheEdgeCheck();

        assertEquals(OperationStatus.OK, processor.processMessage(save(document("compliant", "Alice"))).getStatus());
        assertTrue(stored("compliant"));
    }
}
