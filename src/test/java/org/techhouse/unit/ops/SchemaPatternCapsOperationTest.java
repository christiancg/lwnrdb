package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.resp.SaveSchemaResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SchemaPatternCapsOperationTest {
    private static final String MAX_PATTERNS = "schemaMaxPatterns";
    private static final String INVALID_REGEX = "(unclosed";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        BeforeHookTestSupport.setUpAll();
    }

    @AfterAll
    static void tearDown() throws Exception {
        BeforeHookTestSupport.tearDownAll();
    }

    @BeforeEach
    void reset() throws Exception {
        BeforeHookTestSupport.reset();
        processor.processMessage(new DeleteSchemaRequest(TestGlobals.DB, TestGlobals.COLL));
    }

    @AfterEach
    void restoreDefaults() throws Exception {
        TestUtils.setPrivateField(configuration, MAX_PATTERNS, ConfigKey.SCHEMA_MAX_PATTERNS.defaultValue());
    }

    private static Map<?, ?> compiledPatterns() throws Exception {
        return TestUtils.getPrivateStaticField(Class.forName("org.techhouse.ejson.validate.SchemaPatterns"), "COMPILED",
                Map.class);
    }

    private static String schemaWithPatterns(String... patterns) {
        final var properties = new StringJoiner(",");
        for (var i = 0; i < patterns.length; i++) {
            properties.add("\"p" + i + "\":{\"pattern\":\"" + patterns[i] + "\"}");
        }
        return "{\"properties\":{" + properties + "}}";
    }

    private SaveSchemaRequest saveSchema(String schema) {
        return new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL, eJson.fromJson(schema, JsonObject.class));
    }

    private static SaveRequest save(String id, String value) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("p0", new JsonString(value));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document);
        request.set_id(id);
        return request;
    }

    @Test
    public void test_too_many_patterns_are_refused_before_compiling() throws Exception {
        TestUtils.setPrivateField(configuration, MAX_PATTERNS, 3);

        final var response = processor
                .processMessage(saveSchema(schemaWithPatterns("^caps-a$", "^caps-b$", "^caps-c$", INVALID_REGEX)));

        assertEquals(ErrorCode.SCHEMA_TOO_MANY_PATTERNS.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().startsWith(ErrorCode.SCHEMA_TOO_MANY_PATTERNS.getDefaultMessage()));
        assertFalse(compiledPatterns().containsKey("^caps-a$"));
        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
        assertNull(fs.readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_schema_exactly_at_the_pattern_cap_is_accepted() throws Exception {
        TestUtils.setPrivateField(configuration, MAX_PATTERNS, 3);

        final var response = processor.processMessage(saveSchema(schemaWithPatterns("^at-a$", "^at-b$", "^at-c$")));

        assertInstanceOf(SaveSchemaResponse.class, response);
        assertNotNull(fs.readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_the_length_cap_runs_before_meta_validation() throws Exception {
        final var tooLong = INVALID_REGEX + "a".repeat(5000);

        final var response = processor.processMessage(saveSchema(schemaWithPatterns(tooLong)));

        assertEquals(ErrorCode.SCHEMA_PATTERN_TOO_LONG.getCode(), response.getErrorCode());
        assertNull(fs.readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_stored_schema_over_the_caps_is_loaded_and_still_validates_writes() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, schemaWithPatterns("^stored-[a-z]+$", "^other$"));
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.setPrivateField(configuration, MAX_PATTERNS, 1);

        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(OperationStatus.OK, processor.processMessage(save("stored-ok", "stored-abc")).getStatus());
        assertEquals(ErrorCode.SCHEMA_VALIDATION_FAILED.getCode(),
                processor.processMessage(save("stored-bad", "stored-123")).getErrorCode());
    }
}
