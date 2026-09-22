package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.CustomOperator;
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
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationPipelineTest {
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

    @Test
    public void test_process_multiple_steps_sequence() throws IOException {
        var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        var steps = new ArrayList<BaseAggregationStep>();

        var filterOp = new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonString("value1"));
        steps.add(new FilterAggregationStep(filterOp));

        var mapOps = List.of(new MapOperator(MapOperationType.ADD_FIELD, "field2", null));
        steps.add(new MapAggregationStep(mapOps));

        steps.add(new GroupByAggregationStep("newField2"));
        request.setAggregationSteps(steps);

        var result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
        assertEquals(0, result.size());
    }

    // The engine processes every step type in a single pipeline without error. This drives the
    // engine directly (bypassing request validation), so the COUNT-not-last shape is intentional:
    // it exercises the dispatcher for all step types, not a request the validator would accept.
    @Test
    public void test_engine_processes_all_step_types_in_sequence() throws IOException {
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

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
    }

    @Test
    public void test_process_aggregation_with_no_steps() throws IOException {
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(Collections.emptyList());

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void test_handle_invalid_field_names_in_operations() throws IOException {

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        GroupByAggregationStep groupByStep = new GroupByAggregationStep("invalidField");
        SortAggregationStep sortStep = new SortAggregationStep("invalidField", true);
        JoinAggregationStep joinStep = new JoinAggregationStep("joinCollection", "invalidLocalField",
                "invalidRemoteField", "asField");
        request.setAggregationSteps(List.of(groupByStep, sortStep, joinStep));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
    }

    @Test
    public void test_process_map_with_empty_operator_list() throws IOException {
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        MapAggregationStep mapStep = new MapAggregationStep(Collections.emptyList());
        request.setAggregationSteps(List.of(mapStep));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
        assertEquals(0, result.size());
    }

    private void insertEntry(Cache cache, String id, String fieldName, Object fieldValue) throws IOException {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        if (fieldValue instanceof String s)
            obj.addProperty(fieldName, s);
        else if (fieldValue instanceof Number n)
            obj.addProperty(fieldName, n);
        DbEntry entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
    }

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

    @Test
    public void test_handle_missing_fields() throws IOException {

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        List<BaseAggregationStep> steps = new ArrayList<>();
        steps.add(new GroupByAggregationStep("nonExistentField"));
        request.setAggregationSteps(steps);

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(0, result.size());
    }

    @Test
    public void test_a_full_scan_fallback_closes_its_directory_stream() throws IOException {
        final var closed = new AtomicBoolean();
        final var first = new JsonObject();
        first.add(Globals.PK_FIELD, new JsonString("s1"));
        final var second = new JsonObject();
        second.add(Globals.PK_FIELD, new JsonString("s2"));
        final var source = Stream.of(first, second).onClose(() -> closed.set(true));
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());

        final var results = AggregationOperationHelper.processAggregation(request, source);

        assertEquals(2, results.size());
        assertTrue(closed.get(),
                "the scan fallback holds a Files.list directory handle, and a terminal operation does not close"
                        + " a stream, so every query on a collection without page metadata leaks one descriptor");
    }

    @Test
    public void test_a_pipeline_with_steps_still_closes_its_source() throws IOException {
        final var closed = new AtomicBoolean();
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString("s1"));
        document.add("n", new JsonNumber(1));
        final var source = Stream.of(document).onClose(() -> closed.set(true));
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new LimitAggregationStep(10)));

        AggregationOperationHelper.processAggregation(request, source);

        assertTrue(closed.get());
    }

    private static JsonObject documentWithCustomFields() {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString("c1"));
        document.add("when", new JsonDateTime("#datetime(2024-01-01T10:00:00)"));
        document.add("where", new JsonGeo("#geo(0.0,0.0)"));
        return document;
    }

    private static MapAggregationStep passThroughMapStep() {
        return new MapAggregationStep(List.of(new RemoveFieldMapOperator("absent", null)));
    }

    private List<JsonObject> runOnCustomDocument(List<BaseAggregationStep> steps) throws IOException {
        IocContainer.get(EJson.class);
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(steps);
        return AggregationOperationHelper.processAggregation(request, Stream.of(documentWithCustomFields()));
    }

    @Test
    public void test_a_custom_filter_after_a_map_step_still_matches() throws IOException {
        final var customFilter = new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "when",
                new JsonDateTime("#datetime(2024-01-01T10:00:00)")));

        final var withoutMap = runOnCustomDocument(List.of(customFilter));
        final var withMap = runOnCustomDocument(List.of(passThroughMapStep(), customFilter));

        assertEquals(1, withoutMap.size());
        assertEquals(withoutMap.size(), withMap.size());
        assertTrue(withMap.getFirst().get("when").isJsonCustom());
    }

    @Test
    public void test_a_geo_filter_after_a_map_step_still_matches() throws IOException {
        final var args = new JsonObject();
        args.add("value", new JsonGeo("#geo(0.0,0.0)"));
        args.add("comparator", new JsonString("SMALLER_THAN"));
        args.addProperty("distance", 1000);
        final var geoFilter = new FilterAggregationStep(
                new CustomOperator(JsonGeo.OPERATOR_DISTANCE, "where", new JsonGeo("#geo(0.0,0.0)"), args));

        final var withoutMap = runOnCustomDocument(List.of(geoFilter));
        final var withMap = runOnCustomDocument(List.of(passThroughMapStep(), geoFilter));

        assertEquals(1, withoutMap.size());
        assertEquals(withoutMap.size(), withMap.size());
    }

    @Test
    public void test_a_distinct_step_keeps_custom_values_typed() throws IOException {
        final var result = runOnCustomDocument(List.of(new DistinctAggregationStep(null)));

        assertEquals(1, result.size());
        assertTrue(result.getFirst().get("when").isJsonCustom());
        assertTrue(result.getFirst().get("where").isJsonCustom());
    }
}
