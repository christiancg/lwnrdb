package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.SchemaValidationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveWhereResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ConditionalSaveTest {
    private static final int DOCUMENTS = 40;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "updateMaxMatches", ConfigKey.UPDATE_MAX_MATCHES.defaultValue());
        TestUtils.setPrivateField(configuration, "maxEntrySize", ConfigKey.MAX_ENTRY_SIZE.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void seed() {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var document = new JsonObject();
            document.add(Globals.PK_FIELD, new JsonString("d" + i));
            document.add("group", new JsonString(i % 4 == 0 ? "a" : "b"));
            document.add("n", new JsonNumber(i));
            final var nested = new JsonObject();
            nested.add("kept", new JsonString("k" + i));
            nested.add("other", new JsonString("o"));
            document.add("nested", nested);
            documents.add(document);
        }
        request.setObjects(documents);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private OperationResponse send(String json) {
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    private OperationResponse saveWhere(String condition, String patch) {
        return send("{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"condition\":" + condition + ",\"object\":" + patch + "}");
    }

    private JsonObject find(String id) {
        final var response = send("{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"" + id + "\"}");
        return assertInstanceOf(FindByIdResponse.class, response).getObject();
    }

    private Set<String> ids(String steps) {
        final var response = send("{\"type\":\"AGGREGATE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"aggregationSteps\":" + steps + "}");
        final var ids = new TreeSet<String>();
        if (response instanceof AggregateResponse aggregate) {
            aggregate.getResults().forEach(row -> ids.add(row.get(Globals.PK_FIELD).asJsonString().getValue()));
        }
        return ids;
    }

    private static String equalsGroup(String group) {
        return "{\"fieldOperatorType\":\"EQUALS\",\"field\":\"group\",\"value\":\"" + group + "\"}";
    }

    private static long updatedCount(OperationResponse response) {
        return assertInstanceOf(SaveWhereResponse.class, response).getUpdatedCount();
    }

    private static String text(JsonObject document, String field) {
        return document.get(field).asJsonString().getValue();
    }

    private void assertUntouched(String id) {
        final var document = find(id);
        assertEquals(Integer.parseInt(id.substring(1)) % 4 == 0 ? "a" : "b", text(document, "group"));
        assertFalse(document.has("status"), id + " must not carry the patch");
    }

    @Test
    public void test_updates_every_match_merging_the_patch_and_keeping_every_other_field() {
        seed();

        final var response = saveWhere(equalsGroup("a"), "{\"status\":\"done\",\"group\":\"c\"}");

        assertEquals(10L, updatedCount(response));
        assertEquals(OperationType.SAVE, response.getType());
        for (var i = 0; i < DOCUMENTS; i++) {
            final var document = find("d" + i);
            assertEquals(i, document.get("n").asJsonNumber().getValue().intValue());
            assertEquals("k" + i, text(document.get("nested").asJsonObject(), "kept"));
            if (i % 4 == 0) {
                assertEquals("done", text(document, "status"));
                assertEquals("c", text(document, "group"));
            } else {
                assertUntouched("d" + i);
            }
        }
    }

    @Test
    public void test_no_match_answers_ok_with_zero_and_changes_nothing() {
        seed();

        final var response = saveWhere(equalsGroup("missing"), "{\"status\":\"done\"}");

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(0L, updatedCount(response));
        assertUntouched("d0");
    }

    @Test
    public void test_a_nested_object_replaces_the_stored_one_whole_and_null_is_stored() {
        seed();

        assertEquals(1L, updatedCount(saveWhere("{\"fieldOperatorType\":\"EQUALS\",\"field\":\"n\",\"value\":3}",
                "{\"nested\":{\"fresh\":1},\"group\":null}")));

        final var document = find("d3");
        final var nested = document.get("nested").asJsonObject();
        assertEquals(1, nested.size());
        assertEquals(1, nested.get("fresh").asJsonNumber().getValue().intValue());
        assertTrue(document.get("group").isJsonNull());
    }

    @Test
    public void test_a_patch_on_the_filtered_field_updates_each_match_once() {
        seed();

        final var response = saveWhere("{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"n\",\"value\":10}",
                "{\"n\":100}");

        assertEquals(10L, updatedCount(response));
        assertEquals(10, ids("[{\"type\":\"FILTER\",\"operator\":"
                + "{\"fieldOperatorType\":\"EQUALS\",\"field\":\"n\",\"value\":100}}]").size());
    }

    @Test
    public void test_an_indexed_field_changed_by_the_update_answers_the_same_by_index_and_by_scan() {
        seed();
        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "group")).getStatus());

        assertEquals(10L, updatedCount(saveWhere(equalsGroup("a"), "{\"group\":\"c\"}")));

        final var indexed = ids("[{\"type\":\"FILTER\",\"operator\":" + equalsGroup("c") + "}]");
        final var scanned = ids(
                "[{\"type\":\"SKIP\",\"skip\":0},{\"type\":\"FILTER\",\"operator\":" + equalsGroup("c") + "}]");
        assertEquals(scanned, indexed);
        assertEquals(10, indexed.size());
        assertEquals(Set.of(), ids("[{\"type\":\"FILTER\",\"operator\":" + equalsGroup("a") + "}]"));
    }

    @Test
    public void test_more_than_update_max_matches_is_400_45_and_changes_nothing() throws Exception {
        seed();
        TestUtils.setPrivateField(configuration, "updateMaxMatches", "9");

        final var response = saveWhere(equalsGroup("a"), "{\"status\":\"done\"}");

        assertEquals(ErrorCode.UPDATE_MATCHES_TOO_MANY.getCode(), response.getErrorCode());
        assertEquals(OperationType.SAVE, response.getType());
        assertUntouched("d0");
    }

    @Test
    public void test_exactly_update_max_matches_is_allowed() throws Exception {
        seed();
        TestUtils.setPrivateField(configuration, "updateMaxMatches", "10");

        assertEquals(10L, updatedCount(saveWhere(equalsGroup("a"), "{\"status\":\"done\"}")));
    }

    @Test
    public void test_a_schema_violation_by_one_merged_document_refuses_all_as_a_save() {
        seed();
        assertEquals(OperationStatus.OK,
                send("{\"type\":\"SAVE_SCHEMA\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                        + TestGlobals.COLL + "\",\"schema\":{\"type\":\"object\",\"properties\":"
                        + "{\"status\":{\"type\":\"string\"}}}}").getStatus());

        final var response = saveWhere(equalsGroup("a"), "{\"status\":5}");

        assertEquals(ErrorCode.SCHEMA_VALIDATION_FAILED.getCode(), response.getErrorCode());
        assertEquals(OperationType.SAVE, response.getType());
        assertUntouched("d0");
        assertUntouched("d4");
    }

    private void requireGroupAndN() {
        assertEquals(OperationStatus.OK,
                send("{\"type\":\"SAVE_SCHEMA\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                        + TestGlobals.COLL + "\",\"schema\":{\"type\":\"object\",\"required\":[\"group\",\"n\"],"
                        + "\"properties\":{\"status\":{\"type\":\"string\"}}}}").getStatus());
    }

    @Test
    public void test_a_patch_without_the_required_fields_passes_the_schema_once_merged() {
        seed();
        requireGroupAndN();

        final var response = saveWhere(equalsGroup("a"), "{\"status\":\"done\"}");

        assertEquals(10L, updatedCount(response));
        assertEquals("done", text(find("d4"), "status"));
    }

    @Test
    public void test_the_edge_schema_check_leaves_a_conditional_save_to_the_lock() {
        seed();
        requireGroupAndN();
        final var conditional = RequestParser
                .parseRequest("{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                        + TestGlobals.COLL + "\",\"condition\":" + equalsGroup("a") + ",\"object\":{\"status\":5}}");
        final var plain = RequestParser.parseRequest("{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"object\":{\"status\":\"done\"}}");

        assertNull(SchemaValidationHelper.check(conditional));
        final var plainRefusal = SchemaValidationHelper.check(plain);
        assertNotNull(plainRefusal);
        assertEquals(ErrorCode.SCHEMA_VALIDATION_FAILED.getCode(), plainRefusal.getErrorCode());
        assertEquals(ErrorCode.SCHEMA_VALIDATION_FAILED.getCode(),
                processor.processMessage(conditional).getErrorCode());
        assertUntouched("d0");
    }

    @Test
    public void test_a_merged_document_over_max_entry_size_refuses_all() throws Exception {
        seed();
        TestUtils.setPrivateField(configuration, "maxEntrySize", "1Kb");

        final var response = saveWhere(equalsGroup("a"), "{\"status\":\"" + "x".repeat(2048) + "\"}");

        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals(OperationType.SAVE, response.getType());
        TestUtils.setPrivateField(configuration, "maxEntrySize", ConfigKey.MAX_ENTRY_SIZE.defaultValue());
        assertUntouched("d0");
    }

    @Test
    public void test_a_script_condition_is_evaluated_per_document() {
        seed();

        final var response = saveWhere("{\"script\":\"export default (d) => d.n >= 30;\"}", "{\"status\":\"late\"}");

        assertEquals(10L, updatedCount(response));
        assertEquals("late", text(find("d35"), "status"));
        assertUntouched("d1");
    }

    @Test
    public void test_a_condition_on_an_unregistered_collection_answers_404_11() {
        final var response = send("{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"neverCreated\",\"condition\":" + equalsGroup("a")
                + ",\"object\":{\"status\":\"x\"}}");

        assertEquals("404-11", response.getErrorCode());
    }
}
