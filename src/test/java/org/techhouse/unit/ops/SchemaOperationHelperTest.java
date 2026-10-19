package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.GetSchemaRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.resp.GetSchemaResponse;
import org.techhouse.test.TestGlobals;

public class SchemaOperationHelperTest {
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"_id\"]}";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        BeforeHookTestSupport.setUpAll();
    }

    @AfterAll
    static void tearDown() throws Exception {
        BeforeHookTestSupport.tearDownAll();
    }

    @BeforeEach
    void reset() {
        processor.processMessage(new DeleteSchemaRequest(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_get_schema_returns_saved_schema() {
        final var saved = eJson.fromJson(SCHEMA, JsonObject.class);
        processor.processMessage(new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL, saved));

        final var response = processor.processMessage(new GetSchemaRequest(TestGlobals.DB, TestGlobals.COLL));

        final var schema = assertInstanceOf(GetSchemaResponse.class, response).getSchema();
        assertEquals(OperationType.GET_SCHEMA, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(saved, schema);
        assertNotSame(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL), schema);
    }

    @Test
    public void test_get_schema_without_one_is_404_12() {
        final var response = processor.processMessage(new GetSchemaRequest(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals(ErrorCode.SCHEMA_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_get_schema_unknown_collection_is_404_11() {
        final var response = processor.processMessage(new GetSchemaRequest(TestGlobals.DB, "missingColl"));

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_unreadable_schema_is_503_11() throws Exception {
        processor.processMessage(
                new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL, eJson.fromJson(SCHEMA, JsonObject.class)));
        Files.writeString(schemaFile(), "{not json", StandardCharsets.UTF_8);
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);

        final var response = processor.processMessage(new GetSchemaRequest(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(ErrorCode.SCHEMA_UNAVAILABLE.getCode(), response.getErrorCode());
    }

    private static Path schemaFile() {
        return Path.of(TestGlobals.PATH, TestGlobals.DB, TestGlobals.COLL, TestGlobals.COLL
                + Globals.INDEX_FILE_NAME_SEPARATOR + Globals.SCHEMA_FILE_NAME + Globals.SCHEMA_FILE_EXTENSION);
    }
}
