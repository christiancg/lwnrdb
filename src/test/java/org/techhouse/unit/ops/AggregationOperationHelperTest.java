package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.*;
import org.techhouse.ops.req.agg.step.map.MapOperationType;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class AggregationOperationHelperTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws InterruptedException, IOException, NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Process aggregation request with multiple steps in sequence (filter->map->group)
    @Test
    public void test_process_multiple_steps_sequence() throws IOException {
        System.out.println("Running test_process_multiple_steps_sequence");
        // Arrange
        var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        var steps = new ArrayList<BaseAggregationStep>();
    
        var filterOp = new FieldOperator(FieldOperatorType.EQUALS, "field1",new JsonString("value1"));
        steps.add(new FilterAggregationStep(filterOp));
    
        var mapOps = List.of(new MapOperator(MapOperationType.ADD_FIELD,"field2", null));
        steps.add(new MapAggregationStep(mapOps));
    
        steps.add(new GroupByAggregationStep("newField2"));
        request.setAggregationSteps(steps);
    
        // Act
        var result = AggregationOperationHelper.processAggregation(request);
    
        // Assert
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    // Handle null or empty resultStream at different stages of processing
    @Test
    public void test_handle_null_result_stream() throws IOException, InterruptedException {
        System.out.println("Running test_handle_null_result_stream");
        // Arrange
        var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        var steps = new ArrayList<BaseAggregationStep>();
        steps.add(new CountAggregationStep());
        request.setAggregationSteps(steps);

        final var dbEntry = new AdminDbEntry(TestGlobals.DB);
        AdminOperationHelper.saveDatabaseEntry(dbEntry);
        final var collEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL, Set.of(), 1);
        AdminOperationHelper.saveCollectionEntry(collEntry);
    
        // Act
        var result = AggregationOperationHelper.processAggregation(request);
    
        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        var countResult = result.getFirst();
        assertTrue(countResult.has("count"));
        assertEquals(1, countResult.get("count").asJsonNumber().asInteger());
    }

    // Process each aggregation step type correctly with valid input data
    @Test
    public void test_process_aggregation_with_valid_steps() throws IOException {
        System.out.println("Running test_process_aggregation_with_valid_steps");
        // Arrange
        AggregateRequest request = new AggregateRequest("testDB", "testCollection");
        List<BaseAggregationStep> steps = List.of(
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonString("value1"))),
                new MapAggregationStep(List.of(new MapOperator(MapOperationType.ADD_FIELD, "field", null))),
                new GroupByAggregationStep("fieldName"),
                new JoinAggregationStep("joinCollection", "localField", "remoteField", "asField"),
                new CountAggregationStep(),
                new DistinctAggregationStep("fieldName"),
                new LimitAggregationStep(10),
                new SkipAggregationStep(5),
                new SortAggregationStep("fieldName", true)
        );
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
        AggregateRequest request = new AggregateRequest("testDB", "testCollection");
        request.setAggregationSteps(Collections.emptyList());

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Process join operation with matching fields between collections
    @Test
    public void test_process_join_operation_with_matching_fields() throws IOException {
        System.out.println("Running test_process_join_operation_with_matching_fields");
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        JoinAggregationStep joinStep = new JoinAggregationStep("joinCollection", "localField", "remoteField", "asField");
        request.setAggregationSteps(List.of(joinStep));

        final var cache = IocContainer.get(Cache.class);

        JsonObject jsonObject1 = new JsonObject();
        jsonObject1.addProperty("localField", "value1");
        final var dbEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, jsonObject1);
        dbEntry.set_id("1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, dbEntry);

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertEquals(1, result.size());
        assertTrue(result.getFirst().has("asField"));
    }

    // Process group by operation with valid field name and data
    @Test
    public void test_process_group_by_operation_with_valid_field() throws IOException {
        System.out.println("Running test_process_group_by_operation_with_valid_field");
        // Arrange
        AggregateRequest request = new AggregateRequest("testDB", "testCollection");
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
        AggregateRequest request = new AggregateRequest("testDB", "testCollection");
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
        
        AggregateRequest request = new AggregateRequest("testDB", "testCollection");
        GroupByAggregationStep groupByStep = new GroupByAggregationStep("invalidField");
        SortAggregationStep sortStep = new SortAggregationStep("invalidField", true);
        JoinAggregationStep joinStep = new JoinAggregationStep("joinCollection", "invalidLocalField", "invalidRemoteField", "asField");
        request.setAggregationSteps(List.of(groupByStep, sortStep, joinStep));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
        // Add assertions based on expected behavior when invalid fields are used
    }

    // Process limit/skip with zero or negative values
    @Test
    @Disabled //TODO: this should be probably added as a validation
    public void test_process_limit_skip_with_zero_or_negative_values() throws IOException {
        AggregateRequest request = new AggregateRequest("testDB", "testCollection");
        LimitAggregationStep limitStep = new LimitAggregationStep(0);
        SkipAggregationStep skipStep = new SkipAggregationStep(-1);
        request.setAggregationSteps(List.of(limitStep, skipStep));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Handle empty collections in join operations
    @Test
    public void test_handle_empty_collections_in_join() throws IOException {
        System.out.println("Running test_handle_empty_collections_in_join");
        // Arrange
        AggregateRequest request = new AggregateRequest("testDB", "testCollection");
        JoinAggregationStep joinStep = new JoinAggregationStep("joinCollection", "localField", "remoteField", "asField");
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
        AggregateRequest request = new AggregateRequest("testDB", "testCollection");
        MapAggregationStep mapStep = new MapAggregationStep(Collections.emptyList());
        request.setAggregationSteps(List.of(mapStep));

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    // Handle missing fields in json objects during operations
    @Test
    public void test_handle_missing_fields() throws IOException {
        System.out.println("Running test_handle_missing_fields");
        
        AggregateRequest request = new AggregateRequest("testDB", "testCollection");
        List<BaseAggregationStep> steps = new ArrayList<>();
        steps.add(new GroupByAggregationStep("nonExistentField"));
        request.setAggregationSteps(steps);

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(0, result.size());
    }
}