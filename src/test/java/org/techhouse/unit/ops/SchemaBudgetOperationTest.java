package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.BeforeHookContext;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.SaveSchemaResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.unit.ejson.validate.DiamondSchemas;

public class SchemaBudgetOperationTest {
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

    private SaveSchemaRequest saveSchema(String schema) {
        return new SaveSchemaRequest(TestGlobals.DB, TestGlobals.COLL, eJson.fromJson(schema, JsonObject.class));
    }

    private static JsonObject named(String id, String name) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("name", new JsonString(name));
        return document;
    }

    private static SaveRequest save(JsonObject document) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document);
        request.set_id(document.get("_id").asJsonString().getValue());
        return request;
    }

    private JsonObject find(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request) instanceof FindByIdResponse found ? found.getObject() : null;
    }

    @Test
    public void test_save_schema_over_expanded_limit_answers_400_24_and_writes_nothing() throws Exception {
        final var response = processor.processMessage(saveSchema(DiamondSchemas.chain(28)));

        assertEquals(OperationType.SAVE_SCHEMA, response.getType());
        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals(ErrorCode.SCHEMA_TOO_COMPLEX.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().startsWith(ErrorCode.SCHEMA_TOO_COMPLEX.getDefaultMessage()));
        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
        assertNull(fs.readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_an_oversized_schema_pattern_is_refused_at_save() throws Exception {
        TestUtils.setPrivateField(configuration, "schemaMaxPatternLength", 8);
        try {
            final var tooLong = processor
                    .processMessage(saveSchema("{\"$defs\":{\"d\":{\"patternProperties\":{\"^[a-z]{1,9}$\":{}}}}}"));
            assertEquals(ErrorCode.SCHEMA_PATTERN_TOO_LONG.getCode(), tooLong.getErrorCode());
            assertTrue(tooLong.getMessage().startsWith(ErrorCode.SCHEMA_PATTERN_TOO_LONG.getDefaultMessage()));
            assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
            assertNull(fs.readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
            assertInstanceOf(SaveSchemaResponse.class,
                    processor.processMessage(saveSchema("{\"properties\":{\"name\":{\"pattern\":\"^[a-z]+$\"}}}")));
        } finally {
            TestUtils.setPrivateField(configuration, "schemaMaxPatternLength",
                    ConfigKey.SCHEMA_MAX_PATTERN_LENGTH.defaultValue());
        }
    }

    @Test
    public void test_a_stored_schema_with_a_long_pattern_still_validates_writes() throws Exception {
        TestUtils.setPrivateField(configuration, "schemaMaxPatternLength", 4);
        try {
            cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL,
                    eJson.fromJson("{\"properties\":{\"name\":{\"pattern\":\"^x+y$\"}}}", JsonObject.class));
            assertEquals(OperationStatus.OK, processor.processMessage(save(named("long", "xxy"))).getStatus());
            assertEquals(ErrorCode.SCHEMA_VALIDATION_FAILED.getCode(),
                    processor.processMessage(save(named("long", "xxz"))).getErrorCode());
        } finally {
            TestUtils.setPrivateField(configuration, "schemaMaxPatternLength",
                    ConfigKey.SCHEMA_MAX_PATTERN_LENGTH.defaultValue());
        }
    }

    @Test
    public void test_bulk_save_shares_one_schema_budget_across_documents() throws Exception {
        processor.processMessage(saveSchema("{\"properties\":{\"tags\":{\"items\":{\"type\":\"string\"}}}}"));
        TestUtils.setPrivateField(configuration, "schemaValidationBudget", 1000);
        try {
            final var tagged = new ArrayList<JsonObject>();
            for (var i = 0; i < 4; i++) {
                tagged.add(eJson.fromJson("{\"_id\":\"tagged" + i + "\",\"tags\":[" + "\"t\",".repeat(299) + "\"t\"]}",
                        JsonObject.class));
            }
            assertEquals(OperationStatus.OK, processor.processMessage(save(tagged.getFirst())).getStatus());
            final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObjects(tagged);

            final var response = processor.processMessage(request);

            assertEquals(ErrorCode.SCHEMA_VALIDATION_BUDGET_EXCEEDED.getCode(), response.getErrorCode());
            assertNull(find("tagged3"));
        } finally {
            TestUtils.setPrivateField(configuration, "schemaValidationBudget",
                    ConfigKey.SCHEMA_VALIDATION_BUDGET.defaultValue());
        }
    }

    @Test
    public void test_a_hooked_bulk_save_rechecks_replacements_against_the_request_budget() throws Exception {
        processor.processMessage(saveSchema("{\"properties\":{\"tags\":{\"items\":{\"type\":\"string\"}}}}"));
        BeforeHookTestSupport.installHook("tagger", "tagger", "export default (d) => ({ ...d, hooked: true });",
                EventType.CREATED);
        TestUtils.setPrivateField(configuration, "schemaValidationBudget", 1000);
        try {
            final var tagged = new ArrayList<JsonObject>();
            for (var i = 0; i < 2; i++) {
                tagged.add(eJson.fromJson("{\"_id\":\"hooked" + i + "\",\"tags\":[" + "\"t\",".repeat(299) + "\"t\"]}",
                        JsonObject.class));
            }
            final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObjects(tagged);

            final var response = processor.processMessage(request);

            assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), response.getErrorCode());
            assertTrue(response.getMessage().contains("exceeds the schema validation budget"), response.getMessage());
            assertNull(find("hooked0"));
        } finally {
            TestUtils.setPrivateField(configuration, "schemaValidationBudget",
                    ConfigKey.SCHEMA_VALIDATION_BUDGET.defaultValue());
        }
    }

    @Test
    public void test_save_schema_diamond_chain_under_limit_is_accepted() {
        final var response = processor.processMessage(saveSchema(DiamondSchemas.chain(12)));

        assertInstanceOf(SaveSchemaResponse.class, response);
        assertEquals(OperationStatus.OK, response.getStatus());
        final var stored = BeforeHookTestSupport.document("diamond");
        assertEquals(OperationStatus.OK, processor.processMessage(save(stored)).getStatus());
        assertNotNull(find("diamond"));
    }

    @Test
    public void test_bulk_save_with_one_hostile_document_writes_nothing() {
        processor.processMessage(saveSchema(DiamondSchemas.redosPatternSchema()));
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(named("bulk-ok", "xxy"), named("bulk-bad", DiamondSchemas.hostileName())));

        final var response = processor.processMessage(request);

        assertEquals(ErrorCode.SCHEMA_VALIDATION_BUDGET_EXCEEDED.getCode(), response.getErrorCode());
        assertNull(find("bulk-ok"));
        assertNull(find("bulk-bad"));
    }

    @Test
    public void test_transactional_save_answers_400_23_and_the_transaction_stays_usable() {
        processor.processMessage(saveSchema(DiamondSchemas.redosPatternSchema()));
        final var client = BeforeHookTestSupport.newClient();
        processor.processMessage(new StartTransactionRequest(), client);

        final var refused = processor.processMessage(save(named("tx-bad", DiamondSchemas.hostileName())), client);
        final var accepted = processor.processMessage(save(named("tx-ok", "xxy")), client);
        final var committed = processor.processMessage(new CommitTransactionRequest(), client);

        assertEquals(ErrorCode.SCHEMA_VALIDATION_BUDGET_EXCEEDED.getCode(), refused.getErrorCode());
        assertEquals(OperationStatus.OK, accepted.getStatus());
        assertEquals(OperationStatus.OK, committed.getStatus());
        assertNull(find("tx-bad"));
        assertNotNull(find("tx-ok"));
    }

    @Test
    public void test_hook_replacement_over_budget_is_rejected_not_thrown() throws Exception {
        processor.processMessage(saveSchema(DiamondSchemas.redosPatternSchema()));
        BeforeHookTestSupport.installHook("redos", "redos",
                "export default (d) => ({ ...d, name: 'x'.repeat(800) + '!' });", EventType.CREATED);

        try (var hooks = BeforeHookContext.open(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                BeforeHookTestSupport.ACTOR)) {
            final var outcome = hooks.apply(BeforeHookTestSupport.document("hooked"), "hooked", OperationType.SAVE);

            assertTrue(outcome.isRejected());
            assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), outcome.rejection().getErrorCode());
            assertTrue(outcome.rejection().getMessage().contains("exceeds the schema validation budget"),
                    outcome.rejection().getMessage());
        }
    }
}
