package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.resp.SaveSchemaResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorSchemaTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);

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
    void reset() {
        processor.processMessage(new DeleteSchemaRequest(TestGlobals.DB, TestGlobals.COLL));
    }

    private JsonObject schema(String json) {
        return eJson.fromJson(json, JsonObject.class);
    }

    @Test
    public void test_save_schema_success() {
        final var request = new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL, schema("{\"type\":\"object\"}"));
        final var response = processor.processMessage(request);
        assertInstanceOf(SaveSchemaResponse.class, response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_save_schema_warnings() {
        final var request = new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL,
                schema("{\"type\":\"object\",\"foo\":1}"));
        final var response = (SaveSchemaResponse) processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(response.getWarnings().isEmpty());
    }

    @Test
    public void test_save_invalid_schema() {
        final var request = new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL,
                schema("{\"type\":\"object\",\"required\":\"name\"}"));
        final var response = processor.processMessage(request);
        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("400-8", response.getErrorCode());
        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_save_schema_unknown_collection() {
        final var request = new SaveSchemaRequest(TestGlobals.DB, "no_such_coll", schema("{\"type\":\"object\"}"));
        final var response = processor.processMessage(request);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
    }

    @Test
    public void test_delete_schema() {
        processor.processMessage(
                new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL, schema("{\"type\":\"object\"}")));
        final var response = processor.processMessage(new DeleteSchemaRequest(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(OperationType.DELETE_SCHEMA, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_delete_schema_idempotent() {
        final var response = processor.processMessage(new DeleteSchemaRequest(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(OperationType.DELETE_SCHEMA, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
    }

    @Test
    public void test_schema_survives_cache_eviction() {
        processor.processMessage(
                new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL, schema("{\"type\":\"object\"}")));
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }
}
