package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.MapOperationType;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationOperationHelperTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Process aggregation request with multiple steps in sequence (filter->map->group)
    @Test
    public void test_process_multiple_steps_sequence() throws IOException {
        System.out.println("Running test_process_multiple_steps_sequence");
        // Arrange
        var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        var steps = new ArrayList<BaseAggregationStep>();

        var filterOp = new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonString("value1"));
        steps.add(new FilterAggregationStep(filterOp));

        var mapOps = List.of(new MapOperator(MapOperationType.ADD_FIELD, "field2", null));
        steps.add(new MapAggregationStep(mapOps));

        steps.add(new GroupByAggregationStep("newField2"));
        request.setAggregationSteps(steps);

        // Act
        var result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    // COUNT as the only step on an empty collection returns zero (exercises the
    // null-resultStream branch that derives the count from admin page metadata)
    @Test
    public void test_count_on_empty_collection_returns_zero() throws IOException {
        // Arrange
        var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        var steps = new ArrayList<BaseAggregationStep>();
        steps.add(new CountAggregationStep());
        request.setAggregationSteps(steps);

        // Act
        var result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        var countResult = result.getFirst();
        assertTrue(countResult.has("count"));
        assertEquals(0, countResult.get("count").asJsonNumber().asInteger());
    }

    // The engine processes every step type in a single pipeline without error. This drives the
    // engine directly (bypassing request validation), so the COUNT-not-last shape is intentional:
    // it exercises the dispatcher for all step types, not a request the validator would accept.
    @Test
    public void test_engine_processes_all_step_types_in_sequence() throws IOException {
        System.out.println("Running test_engine_processes_all_step_types_in_sequence");
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        List<BaseAggregationStep> steps = List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonString("value1"))),
                new MapAggregationStep(List.of(new MapOperator(MapOperationType.ADD_FIELD, "field", null))),
                new GroupByAggregationStep("fieldName"),
                new JoinAggregationStep("joinCollection", "localField", "remoteField", "asField"),
                new CountAggregationStep(), new DistinctAggregationStep("fieldName"), new LimitAggregationStep(10),
                new SkipAggregationStep(5), new SortAggregationStep("fieldName", true));
        request.setAggregationSteps(steps);

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertNotNull(result);
        // Further assertions can be added based on expected behavior
    }

    // Return empty list when no aggregation steps provided
    @Test
    public void test_process_aggregation_with_no_steps() throws IOException {
        System.out.println("Running test_process_aggregation_with_no_steps");
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(Collections.emptyList());

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Process group by operation with valid field name and data
    @Test
    public void test_process_group_by_operation_with_valid_field() throws IOException {
        System.out.println("Running test_process_group_by_operation_with_valid_field");
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        GroupByAggregationStep groupByStep = new GroupByAggregationStep("groupField");
        request.setAggregationSteps(List.of(groupByStep));

        JsonObject jsonObject1 = new JsonObject();
        jsonObject1.addProperty("groupField", "value1");
        JsonObject jsonObject2 = new JsonObject();
        jsonObject2.addProperty("groupField", "value1");

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertEquals(0, result.size());
    }

    // Process distinct operation on specific field with valid data
    @Test
    public void test_process_distinct_operation_on_specific_field() throws IOException {
        System.out.println("Running test_process_distinct_operation_on_specific_field");
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        DistinctAggregationStep distinctStep = new DistinctAggregationStep("distinctField");
        request.setAggregationSteps(List.of(distinctStep));

        JsonObject jsonObject1 = new JsonObject();
        jsonObject1.addProperty("distinctField", "value1");
        JsonObject jsonObject2 = new JsonObject();
        jsonObject2.addProperty("distinctField", "value2");
        JsonObject jsonObject3 = new JsonObject();
        jsonObject3.addProperty("distinctField", "value1");

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertEquals(0, result.size());
    }

    // Handle invalid field names in sort/group/join operations
    @Test
    public void test_handle_invalid_field_names_in_operations() throws IOException {
        System.out.println("Running test_handle_invalid_field_names_in_operations");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        GroupByAggregationStep groupByStep = new GroupByAggregationStep("invalidField");
        SortAggregationStep sortStep = new SortAggregationStep("invalidField", true);
        JoinAggregationStep joinStep = new JoinAggregationStep("joinCollection", "invalidLocalField",
                "invalidRemoteField", "asField");
        request.setAggregationSteps(List.of(groupByStep, sortStep, joinStep));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
        // Add assertions based on expected behavior when invalid fields are used
    }

    // Handle empty collections in join operations
    @Test
    public void test_handle_empty_collections_in_join() throws IOException {
        System.out.println("Running test_handle_empty_collections_in_join");
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        JoinAggregationStep joinStep = new JoinAggregationStep("joinCollection", "localField", "remoteField",
                "asField");
        request.setAggregationSteps(List.of(joinStep));

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Process map operations with empty operator list
    @Test
    public void test_process_map_with_empty_operator_list() throws IOException {
        System.out.println("Running test_process_map_with_empty_operator_list");
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        MapAggregationStep mapStep = new MapAggregationStep(Collections.emptyList());
        request.setAggregationSteps(List.of(mapStep));

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    // Helper to insert entries directly into cache and page metadata for the test collection
    private void insertEntry(Cache cache, String id, String fieldName, Object fieldValue) {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        if (fieldValue instanceof String s)
            obj.addProperty(fieldName, s);
        else if (fieldValue instanceof Number n)
            obj.addProperty(fieldName, n);
        DbEntry entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
    }

    // COUNT returns the number of documents in the collection
    @Test
    public void test_count_returns_document_count() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "c1", "val", "a");
        insertEntry(cache, "c2", "val", "b");
        insertEntry(cache, "c3", "val", "c");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new CountAggregationStep()));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().has("count"));
        assertEquals(3, result.getFirst().get("count").asJsonNumber().asInteger());
    }

    // DISTINCT returns only unique values for the given field
    @Test
    public void test_distinct_returns_unique_values() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "d1", "color", "red");
        insertEntry(cache, "d2", "color", "blue");
        insertEntry(cache, "d3", "color", "red");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new DistinctAggregationStep("color")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(2, result.size());
    }

    // SORT ascending orders documents by the given field
    @Test
    public void test_sort_ascending_orders_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "s1", "score", 30);
        insertEntry(cache, "s2", "score", 10);
        insertEntry(cache, "s3", "score", 20);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep("score", true)));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(3, result.size());
        assertEquals(10, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(20, result.get(1).get("score").asJsonNumber().asInteger());
        assertEquals(30, result.get(2).get("score").asJsonNumber().asInteger());
    }

    // SORT descending orders documents by the given field in reverse
    @Test
    public void test_sort_descending_orders_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "sd1", "score", 30);
        insertEntry(cache, "sd2", "score", 10);
        insertEntry(cache, "sd3", "score", 20);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep("score", false)));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(3, result.size());
        assertEquals(30, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(20, result.get(1).get("score").asJsonNumber().asInteger());
        assertEquals(10, result.get(2).get("score").asJsonNumber().asInteger());
    }

    // GROUP_BY groups documents by a given field value
    @Test
    public void test_group_by_groups_documents_by_field() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "g1", "type", "A");
        insertEntry(cache, "g2", "type", "B");
        insertEntry(cache, "g3", "type", "A");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new GroupByAggregationStep("type")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(2, result.size());
    }

    // SKIP skips the first N documents
    @Test
    public void test_skip_skips_n_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "sk1", "n", 1);
        insertEntry(cache, "sk2", "n", 2);
        insertEntry(cache, "sk3", "n", 3);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep("n", true), new SkipAggregationStep(2)));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(1, result.size());
        assertEquals(3, result.getFirst().get("n").asJsonNumber().asInteger());
    }

    // LIMIT limits the result to the first N documents
    @Test
    public void test_limit_limits_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "lim1", "n", 1);
        insertEntry(cache, "lim2", "n", 2);
        insertEntry(cache, "lim3", "n", 3);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep("n", true), new LimitAggregationStep(2)));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(2, result.size());
    }

    // JOIN adds matched documents from another collection as a nested array field
    @Test
    public void test_join_adds_matching_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);

        JsonObject mainDoc = new JsonObject();
        mainDoc.add(Globals.PK_FIELD, new JsonString("main1"));
        mainDoc.addProperty("ref", 42);
        DbEntry mainEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, mainDoc);
        mainEntry.set_id("main1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, mainEntry);

        JsonObject joinDoc = new JsonObject();
        joinDoc.add(Globals.PK_FIELD, new JsonString("join1"));
        joinDoc.addProperty("refKey", 42);
        joinDoc.addProperty("label", "matched");
        DbEntry joinEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.JOIN_COLL, joinDoc);
        joinEntry.set_id("join1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.JOIN_COLL, joinEntry);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(r -> r.has("joined") && !r.get("joined").asJsonArray().isEmpty()));
    }

    // DISTINCT with null fieldName returns unique objects (removes _id) (L138-145)
    @Test
    public void test_distinct_with_null_field_name_returns_unique_objects() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "dn1", "color", "red");
        insertEntry(cache, "dn2", "color", "red");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new DistinctAggregationStep(null)));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);
        // null fieldName → distinct on whole object minus _id
        assertNotNull(result);
        // Both entries have same fields, so distinct should collapse to 1
        assertEquals(1, result.size());
    }

    // DISTINCT with empty fieldName also removes _id and deduplicates (L138-145)
    @Test
    public void test_distinct_with_empty_field_name() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "de1", "x", "same");
        insertEntry(cache, "de2", "x", "same");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new DistinctAggregationStep("")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    // JOIN skips objects that lack the local field (L109, L115)
    @Test
    public void test_join_skips_objects_without_local_field() throws IOException {
        final var cache = IocContainer.get(Cache.class);

        // Entry without the localField
        JsonObject noField = new JsonObject();
        noField.add(Globals.PK_FIELD, new JsonString("nf1"));
        noField.addProperty("other", "value");
        DbEntry nfEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, noField);
        nfEntry.set_id("nf1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, nfEntry);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(
                List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "missingField", "refKey", "joined")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);
        assertNotNull(result);
        // Object without localField should still appear but with empty joined array
        assertTrue(result.stream().allMatch(r -> !r.has("joined") || r.get("joined").asJsonArray().isEmpty()));
    }

    // MAP step with actual data exercises the lambda body (L61-62)
    @Test
    public void test_map_step_processes_actual_data() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "map1", "score", 10);
        insertEntry(cache, "map2", "score", 20);

        JsonArray operands = new JsonArray();
        operands.add(new JsonString("score"));
        operands.add(new JsonNumber(5));
        AggregateRequest request = getAggregateRequest(operands);

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(r -> r.has("total")));
    }

    private static @NonNull AggregateRequest getAggregateRequest(JsonArray operands) {
        org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator sumOp = new org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator(
                org.techhouse.ops.req.agg.mid_operators.MidOperationType.SUM, operands);
        org.techhouse.ops.req.agg.step.map.AddFieldMapOperator mapOp = new org.techhouse.ops.req.agg.step.map.AddFieldMapOperator(
                "total", null, sumOp);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new MapAggregationStep(List.of(mapOp))));
        return request;
    }

    // Handle missing fields in json objects during operations
    @Test
    public void test_handle_missing_fields() throws IOException {
        System.out.println("Running test_handle_missing_fields");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        List<BaseAggregationStep> steps = new ArrayList<>();
        steps.add(new GroupByAggregationStep("nonExistentField"));
        request.setAggregationSteps(steps);

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(0, result.size());
    }

    // ---- Index-backed aggregation steps (GROUP_BY, JOIN, SORT, DISTINCT) ----

    private void addDoc(Cache cache, String id, String field, JsonBaseElement value) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(field, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void enableIndex(Cache cache, String field) {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(field));
    }

    private void addJoinDoc(Cache cache, String id, int refKey, String label) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.addProperty("refKey", refKey);
        obj.addProperty("label", label);
        final var e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.JOIN_COLL, obj);
        e.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.JOIN_COLL, e);
    }

    // DISTINCT over an indexed field returns the same values as a non-indexed scan
    @Test
    public void test_distinct_uses_index_returns_same_values_as_scan() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "color", new JsonString("red"));
        addDoc(cache, "c2", "color", new JsonString("blue"));
        addDoc(cache, "c3", "color", new JsonString("red"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var scanValues = AggregationOperationHelper.processAggregation(req).stream()
                .map(o -> o.get("color").asJsonString().getValue()).collect(java.util.stream.Collectors.toSet());

        enableIndex(cache, "color");
        final var indexed = AggregationOperationHelper.processAggregation(req);
        final var indexValues = indexed.stream().map(o -> o.get("color").asJsonString().getValue())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(2, indexed.size());
        assertEquals(scanValues, indexValues);
    }

    // Index-backed DISTINCT does not read any documents (still works after the doc cache is evicted)
    @Test
    public void test_distinct_indexed_reads_no_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "color", new JsonString("red"));
        addDoc(cache, "c2", "color", new JsonString("blue"));
        enableIndex(cache, "color");
        IocContainer.get(org.techhouse.cache.UserCache.class).evictCollectionDocuments(TestGlobals.DB,
                TestGlobals.COLL);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, result.size());
    }

    // GROUP_BY over an indexed field produces the same groups as a non-indexed scan
    @Test
    public void test_group_by_uses_index_groups_match_scan() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "g1", "type", new JsonString("A"));
        addDoc(cache, "g2", "type", new JsonString("B"));
        addDoc(cache, "g3", "type", new JsonString("A"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new GroupByAggregationStep("type")));
        final var scan = AggregationOperationHelper.processAggregation(req);

        enableIndex(cache, "type");
        final var indexed = AggregationOperationHelper.processAggregation(req);

        assertEquals(scan.size(), indexed.size());
        assertEquals(2, indexed.size());
        final var groupA = indexed.stream().filter(o -> o.get("type").asJsonString().getValue().equals("A")).findFirst()
                .orElseThrow();
        assertEquals(2, groupA.get("group").asJsonArray().size());
        final var groupB = indexed.stream().filter(o -> o.get("type").asJsonString().getValue().equals("B")).findFirst()
                .orElseThrow();
        assertEquals(1, groupB.get("group").asJsonArray().size());
    }

    // SORT ascending over an indexed field orders documents like the non-indexed path
    @Test
    public void test_sort_ascending_uses_index_matches_scan_order() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "s1", "score", new JsonNumber(30));
        addDoc(cache, "s2", "score", new JsonNumber(10));
        addDoc(cache, "s3", "score", new JsonNumber(20));
        enableIndex(cache, "score");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new SortAggregationStep("score", true)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(3, result.size());
        assertEquals(10, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(20, result.get(1).get("score").asJsonNumber().asInteger());
        assertEquals(30, result.get(2).get("score").asJsonNumber().asInteger());
    }

    // SORT descending over an indexed field orders documents in reverse
    @Test
    public void test_sort_descending_uses_index_matches_scan_order() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "s1", "score", new JsonNumber(30));
        addDoc(cache, "s2", "score", new JsonNumber(10));
        addDoc(cache, "s3", "score", new JsonNumber(20));
        enableIndex(cache, "score");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new SortAggregationStep("score", false)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(3, result.size());
        assertEquals(30, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(20, result.get(1).get("score").asJsonNumber().asInteger());
        assertEquals(10, result.get(2).get("score").asJsonNumber().asInteger());
    }

    // JOIN using a remote-field index attaches only the matching remote documents
    @Test
    public void test_join_uses_remote_index_returns_only_matching() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        final var main = new JsonObject();
        main.add(Globals.PK_FIELD, new JsonString("m1"));
        main.addProperty("ref", 42);
        final var mainEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, main);
        mainEntry.set_id("m1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, mainEntry);

        addJoinDoc(cache, "j1", 42, "matched");
        addJoinDoc(cache, "j2", 7, "nope");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.JOIN_COLL, "refKey");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.JOIN_COLL).setIndexes(Set.of("refKey"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(1, result.size());
        final var joined = result.getFirst().get("joined").asJsonArray();
        assertEquals(1, joined.size());
        assertEquals("matched", joined.get(0).asJsonObject().get("label").asJsonString().getValue());
    }

    // DISTINCT with a null field name ignores the index and deduplicates whole documents
    @Test
    public void test_distinct_null_field_ignores_index() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "d1", "color", new JsonString("red"));
        addDoc(cache, "d2", "color", new JsonString("red"));
        enableIndex(cache, "color");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep(null)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(1, result.size());
    }

    // GROUP_BY after a FILTER must operate on the filtered subset (no index fast-path)
    @Test
    public void test_group_by_with_upstream_filter_does_not_use_index() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "g1", "type", new JsonString("A"));
        addDoc(cache, "g2", "type", new JsonString("B"));
        addDoc(cache, "g3", "type", new JsonString("A"));
        enableIndex(cache, "type");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "type", new JsonString("A"))),
                new GroupByAggregationStep("type")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(1, result.size());
        assertEquals("A", result.getFirst().get("type").asJsonString().getValue());
        assertEquals(2, result.getFirst().get("group").asJsonArray().size());
    }

    // SORT after a FILTER must sort only the filtered subset (no index fast-path)
    @Test
    public void test_sort_with_upstream_stream_does_not_use_index() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "s1", "score", new JsonNumber(30));
        addDoc(cache, "s2", "score", new JsonNumber(10));
        addDoc(cache, "s3", "score", new JsonNumber(20));
        enableIndex(cache, "score");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.GREATER_THAN, "score", new JsonNumber(10))),
                new SortAggregationStep("score", true)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, result.size());
        assertEquals(20, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(30, result.get(1).get("score").asJsonNumber().asInteger());
    }

    // Without an index the step falls back to the scan path
    @Test
    public void test_distinct_without_index_falls_back_to_scan() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "color", new JsonString("red"));
        addDoc(cache, "c2", "color", new JsonString("blue"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, result.size());
    }

    // Option B: documents whose indexed field holds an object/array are not in the index, so an
    // index-backed DISTINCT does not include them
    @Test
    public void test_object_valued_field_excluded_from_indexed_distinct() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "o1", "data", new JsonString("scalar"));
        final var objVal = new JsonObject();
        objVal.addProperty("nested", 1);
        addDoc(cache, "o2", "data", objVal);
        enableIndex(cache, "data");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("data")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(1, result.size());
        assertEquals("scalar", result.getFirst().get("data").asJsonString().getValue());
    }

    // An indexed step on an empty collection returns no results
    @Test
    public void test_indexed_distinct_on_empty_collection_returns_empty() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        enableIndex(cache, "color");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(0, result.size());
    }

    // ---- Index usage in COUNT (FILTER directly followed by COUNT) ----

    private static int countOf(List<JsonObject> result) {
        assertEquals(1, result.size());
        assertTrue(result.getFirst().has("count"));
        return result.getFirst().get("count").asJsonNumber().asInteger();
    }

    private void addDocWithFields(Cache cache, String id, JsonBaseElement v1, String f2, JsonBaseElement v2) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("status", v1);
        obj.add(f2, v2);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    // COUNT after an indexed FILTER is answered from the index id-set size without reading any
    // documents (still correct after the document cache is evicted).
    @Test
    public void test_count_after_indexed_filter_reads_no_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        addDoc(cache, "c2", "status", new JsonString("inactive"));
        addDoc(cache, "c3", "status", new JsonString("active"));
        enableIndex(cache, "status");
        IocContainer.get(org.techhouse.cache.UserCache.class).evictCollectionDocuments(TestGlobals.DB,
                TestGlobals.COLL);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep()));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, countOf(result));
    }

    // Without an index the COUNT after a FILTER falls back to counting the scanned, filtered stream.
    @Test
    public void test_count_after_unindexed_filter_falls_back_and_is_correct() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        addDoc(cache, "c2", "status", new JsonString("inactive"));
        addDoc(cache, "c3", "status", new JsonString("active"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep()));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, countOf(result));
    }

    // The index-backed COUNT yields the same number as the unindexed scan path.
    @Test
    public void test_count_after_filter_indexed_matches_unindexed() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        addDoc(cache, "c2", "status", new JsonString("inactive"));
        addDoc(cache, "c3", "status", new JsonString("active"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep()));
        final var scanCount = countOf(AggregationOperationHelper.processAggregation(req));

        enableIndex(cache, "status");
        final var indexedCount = countOf(AggregationOperationHelper.processAggregation(req));

        assertEquals(scanCount, indexedCount);
        assertEquals(2, indexedCount);
    }

    // Engine robustness: the request validator rejects a COUNT that is not the last step, but if
    // such a pipeline reaches the engine directly the fast-path {count:N} stream is still fed to the
    // trailing steps.
    @Test
    public void test_count_after_filter_with_trailing_step_still_runs() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        addDoc(cache, "c2", "status", new JsonString("active"));
        enableIndex(cache, "status");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep(), new SkipAggregationStep(1)));
        final var result = AggregationOperationHelper.processAggregation(req);

        // The single count object is skipped, proving the trailing step executed on the fast-path stream.
        assertTrue(result.isEmpty());
    }

    // COUNT after an indexed AND conjunction counts the intersection of the matched id-sets.
    @Test
    public void test_count_after_indexed_and_conjunction() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "level", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "level", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "level", new JsonNumber(1));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "level");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status", "level"));

        final var and = new org.techhouse.ops.req.agg.operators.ConjunctionOperator(
                org.techhouse.ops.req.agg.ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new FilterAggregationStep(and), new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // COUNT after an indexed OR conjunction counts the union of the matched id-sets.
    @Test
    public void test_count_after_indexed_or_conjunction() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "level", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "level", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "level", new JsonNumber(3));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "level");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status", "level"));

        final var or = new org.techhouse.ops.req.agg.operators.ConjunctionOperator(
                org.techhouse.ops.req.agg.ConjunctionOperatorType.OR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(3))));
        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new FilterAggregationStep(or), new CountAggregationStep()));

        // c1 + c2 (active) ∪ c3 (level 3) = 3
        assertEquals(3, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // A conjunction with one unindexed leaf cannot use the fast path, but still counts correctly.
    @Test
    public void test_count_after_partially_indexed_conjunction_falls_back() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "level", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "level", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "level", new JsonNumber(1));
        // Only "status" is indexed; "level" is not.
        enableIndex(cache, "status");

        final var and = new org.techhouse.ops.req.agg.operators.ConjunctionOperator(
                org.techhouse.ops.req.agg.ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new FilterAggregationStep(and), new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // COUNT is not the second step, so the fast path does not fire (still correct).
    @Test
    public void test_count_only_step_is_unaffected() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
        enableIndex(cache, "status");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // An indexed FILTER on a value with no matches yields a count of zero (empty id-set, not a fallback).
    @Test
    public void test_count_after_indexed_filter_no_matches_returns_zero() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        enableIndex(cache, "status");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("missing"))),
                new CountAggregationStep()));

        assertEquals(0, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // Sequential indexed FILTER steps compose as AND: the count is the intersection of their id-sets.
    @Test
    public void test_count_after_multiple_indexed_filters_intersects() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "level", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "level", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "level", new JsonNumber(1));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "level");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status", "level"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))),
                new CountAggregationStep()));

        // active = {c1,c2}; level 1 = {c1,c3}; intersection = {c1}
        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // A count-preserving MAP between the FILTER and COUNT is skipped (still reads no documents).
    @Test
    public void test_count_after_indexed_filter_then_map_skips_map() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        addDoc(cache, "c2", "status", new JsonString("inactive"));
        addDoc(cache, "c3", "status", new JsonString("active"));
        enableIndex(cache, "status");
        IocContainer.get(org.techhouse.cache.UserCache.class).evictCollectionDocuments(TestGlobals.DB,
                TestGlobals.COLL);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new MapAggregationStep(
                        List.of(new org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator("extra", null))),
                new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // A count-preserving JOIN between the FILTER and COUNT is skipped.
    @Test
    public void test_count_after_indexed_filter_then_join_skips_join() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "ref", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "ref", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "ref", new JsonNumber(1));
        enableIndex(cache, "status");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined"), new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // A count-preserving SORT between the FILTER and COUNT is skipped.
    @Test
    public void test_count_after_indexed_filter_then_sort_skips_sort() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        addDoc(cache, "c2", "status", new JsonString("active"));
        addDoc(cache, "c3", "status", new JsonString("inactive"));
        enableIndex(cache, "status");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new SortAggregationStep("status", true), new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // With no FILTER, count-preserving steps before COUNT yield the whole-collection count.
    @Test
    public void test_count_after_map_only_uses_whole_collection_count() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "c1", "status", "active");
        insertEntry(cache, "c2", "status", "inactive");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new MapAggregationStep(
                        List.of(new org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator("extra", null))),
                new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // GROUP_BY changes the count, so the fast path is disabled and the group count is returned.
    @Test
    public void test_count_after_group_by_falls_back_to_group_count() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        addDoc(cache, "c2", "status", new JsonString("active"));
        enableIndex(cache, "status");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new GroupByAggregationStep("status"), new CountAggregationStep()));

        // Two active docs collapse into one "status" group, so the count is the group count (1).
        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // A FILTER after a MAP cannot use its index, so the whole pipeline runs normally (count correct).
    @Test
    public void test_count_with_filter_after_map_falls_back() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        addDoc(cache, "c2", "status", new JsonString("inactive"));
        addDoc(cache, "c3", "status", new JsonString("active"));
        enableIndex(cache, "status");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new MapAggregationStep(
                        List.of(new org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator("extra", null))),
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    // LIMIT changes the count, so the fast path is disabled and the capped count is returned.
    @Test
    public void test_count_after_limit_falls_back() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "status", new JsonString("active"));
        addDoc(cache, "c2", "status", new JsonString("active"));
        enableIndex(cache, "status");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new LimitAggregationStep(1), new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }
}
