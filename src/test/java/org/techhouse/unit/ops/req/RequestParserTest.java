package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.*;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RequestParserTest {
    // Successfully parse basic operation requests like SAVE, DELETE, CREATE_DATABASE
    @Test
    public void test_parse_basic_operation_requests() {
        String saveRequest = """
            {
                "type": "SAVE",
                "databaseName": "testDb",
                "collectionName": "testCollection",
                "object": {
                    "name": "test"
                }
            }""";

        String deleteRequest = """
            {
                "type": "DELETE",
                "databaseName": "testDb",
                "collectionName": "testCollection"
            }""";

        String createDbRequest = """
            {
                "type": "CREATE_DATABASE",
                "databaseName": "newDb",
                "collectionName": null
            }""";

        OperationRequest saveResult = RequestParser.parseRequest(saveRequest);
        OperationRequest deleteResult = RequestParser.parseRequest(deleteRequest);
        OperationRequest createDbResult = RequestParser.parseRequest(createDbRequest);

        assertInstanceOf(SaveRequest.class, saveResult);
        assertEquals(OperationType.SAVE, saveResult.getType());
        assertEquals("testDb", saveResult.getDatabaseName());

        assertInstanceOf(DeleteRequest.class, deleteResult);
        assertEquals(OperationType.DELETE, deleteResult.getType());
        assertEquals("testDb", deleteResult.getDatabaseName());

        assertInstanceOf(CreateDatabaseRequest.class, createDbResult);
        assertEquals(OperationType.CREATE_DATABASE, createDbResult.getType());
        assertEquals("newDb", createDbResult.getDatabaseName());
    }

    // Handle null values in JSON fields
    @Test
    public void test_handle_null_json_fields() {
        String requestWithNulls = """
            {
                "type": "SAVE",
                "databaseName": "testDb",
                "collectionName": "testCollection",
                "object": {
                    "name": null,
                    "age": null,
                    "address": null
                }
            }""";

        OperationRequest result = RequestParser.parseRequest(requestWithNulls);

        assertInstanceOf(SaveRequest.class, result);
        SaveRequest saveRequest = (SaveRequest) result;
        JsonObject obj = saveRequest.getObject();

        assertTrue(obj.get("name").isJsonNull());
        assertTrue(obj.get("age").isJsonNull());
        assertTrue(obj.get("address").isJsonNull());
    }

    // Parse aggregation request with filter steps and field operators
    @Test
    public void test_parse_aggregation_request_with_filter_steps_and_field_operators() {
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{ \"type\": \"FILTER\", \"operator\": { \"fieldOperatorType\": \"EQUALS\", \"field\": \"age\", \"value\": {\"$numberInt\": 30} } }] }";
        OperationRequest request = RequestParser.parseRequest(message);
        assertInstanceOf(AggregateRequest.class, request);
        AggregateRequest aggRequest = (AggregateRequest) request;
        assertEquals(1, aggRequest.getAggregationSteps().size());
        BaseAggregationStep step = aggRequest.getAggregationSteps().getFirst();
        assertInstanceOf(FilterAggregationStep.class, step);
        FilterAggregationStep filterStep = (FilterAggregationStep) step;
        assertInstanceOf(FieldOperator.class, filterStep.getOperator());
        FieldOperator fieldOperator = (FieldOperator) filterStep.getOperator();
        assertEquals(FieldOperatorType.EQUALS, fieldOperator.getFieldOperatorType());
        assertEquals("age", fieldOperator.getField());
        final var value = fieldOperator.getValue().asJsonObject().get("$numberInt").asJsonNumber().getValue();
        assertEquals(30, value);
    }

    // Parse map operations with add field and remove field operators
    @Test
    public void test_parse_map_operations_with_add_and_remove_field_operators() {
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{ \"type\": \"MAP\", \"operators\": [{ \"fieldName\": \"newField\", \"operator\": { \"type\": \"SUM\", \"operands\": [\"aField\", 30] }}] }] }";
        OperationRequest request = RequestParser.parseRequest(message);
        assertInstanceOf(AggregateRequest.class, request);
        AggregateRequest aggRequest = (AggregateRequest) request;
        assertEquals(1, aggRequest.getAggregationSteps().size());
        BaseAggregationStep step = aggRequest.getAggregationSteps().getFirst();
        assertInstanceOf(MapAggregationStep.class, step);
        MapAggregationStep mapStep = (MapAggregationStep) step;
        List<MapOperator> operators = mapStep.getOperators();
        assertEquals(1, operators.size());
        assertInstanceOf(AddFieldMapOperator.class, operators.getFirst());
        AddFieldMapOperator addOp = (AddFieldMapOperator) operators.getFirst();
        assertEquals("newField", addOp.getFieldName());
        assertInstanceOf(ArrayParamMidOperator.class, addOp.getOperator());
        ArrayParamMidOperator addParamOperator = (ArrayParamMidOperator) addOp.getOperator();
        assertEquals("aField", addParamOperator.getOperands().asList().getFirst().asJsonString().getValue());
    }

    // Parse conjunction operators with nested operators (AND, OR)
    @Test
    public void test_parse_conjunction_operators_with_nested_operators() {
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{ \"type\": \"FILTER\", \"operator\": { \"conjunctionType\": \"AND\", \"operators\": [{\"fieldOperatorType\": \"EQUALS\", \"field\": \"status\", \"value\": {\"$string\": \"active\"}}, {\"fieldOperatorType\": \"GREATER_THAN\", \"field\": \"age\", \"value\": {\"$numberInt\": 18}}] } }] }";
        OperationRequest request = RequestParser.parseRequest(message);
        assertInstanceOf(AggregateRequest.class, request);
        AggregateRequest aggRequest = (AggregateRequest) request;
        assertEquals(1, aggRequest.getAggregationSteps().size());
        BaseAggregationStep step = aggRequest.getAggregationSteps().getFirst();
        assertInstanceOf(FilterAggregationStep.class, step);
        FilterAggregationStep filterStep = (FilterAggregationStep) step;
        assertInstanceOf(ConjunctionOperator.class, filterStep.getOperator());
        ConjunctionOperator conjunctionOp = (ConjunctionOperator) filterStep.getOperator();
        assertEquals(ConjunctionOperatorType.AND, conjunctionOp.getConjunctionType());
        List<BaseOperator> nestedOperators = conjunctionOp.getOperators();
        assertEquals(2, nestedOperators.size());
        assertInstanceOf(FieldOperator.class, nestedOperators.getFirst());
        FieldOperator firstOp = (FieldOperator) nestedOperators.getFirst();
        assertEquals(FieldOperatorType.EQUALS, firstOp.getFieldOperatorType());
        assertEquals("status", firstOp.getField());
        assertEquals(new JsonString("active"), firstOp.getValue().asJsonObject().get("$string").asJsonString());
        assertInstanceOf(FieldOperator.class, nestedOperators.get(1));
        FieldOperator secondOp = (FieldOperator) nestedOperators.get(1);
        assertEquals(FieldOperatorType.GREATER_THAN, secondOp.getFieldOperatorType());
        assertEquals("age", secondOp.getField());
        assertEquals(new JsonNumber(18), secondOp.getValue().asJsonObject().get("$numberInt").asJsonNumber());
    }

    // Parse mid operators with array parameters (AVG, SUM, etc)
    @Test
    public void test_parse_mid_operators_with_array_parameters() {
        String jsonMessage = """
            {
                "type": "AGGREGATE",
                "databaseName": "testDB",
                "collectionName": "testCollection",
                "aggregationSteps": [
                    {
                        "type": "MAP",
                        "operators": [
                            {
                                "fieldName": "result",
                                "operator": {
                                    "type": "SUM",
                                    "operands": [1, 2, 3]
                                }
                            }
                        ]
                    }
                ]
            }
        """;
        OperationRequest request = RequestParser.parseRequest(jsonMessage);
        assertInstanceOf(AggregateRequest.class, request);
        AggregateRequest aggregateRequest = (AggregateRequest) request;
        List<BaseAggregationStep> steps = aggregateRequest.getAggregationSteps();
        assertEquals(1, steps.size());
        assertInstanceOf(MapAggregationStep.class, steps.getFirst());
        MapAggregationStep mapStep = (MapAggregationStep) steps.getFirst();
        List<MapOperator> operators = mapStep.getOperators();
        assertEquals(1, operators.size());
        assertInstanceOf(AddFieldMapOperator.class, operators.getFirst());
        AddFieldMapOperator addFieldOperator = (AddFieldMapOperator) operators.getFirst();
        assertEquals("result", addFieldOperator.getFieldName());
        assertInstanceOf(ArrayParamMidOperator.class, addFieldOperator.getOperator());
        ArrayParamMidOperator midOperator = (ArrayParamMidOperator) addFieldOperator.getOperator();
        assertEquals(MidOperationType.SUM, midOperator.getType());
    }

    // Process requests with primary key field correctly
    @Test
    public void test_process_requests_with_primary_key_field() {
        String jsonMessage = """
            {
                "type": "SAVE",
                "databaseName": "testDB",
                "collectionName": "testCollection",
                "object": {
                    "_id": "12345",
                    "name": "test"
                }
            }
        """;
        OperationRequest request = RequestParser.parseRequest(jsonMessage);
        assertInstanceOf(SaveRequest.class, request);
        SaveRequest saveRequest = (SaveRequest) request;
        assertEquals("12345", saveRequest.get_id());
        JsonObject object = saveRequest.getObject();
        assertTrue(object.has("_id"));
        assertEquals("12345", object.get("_id").asJsonString().getValue());
    }

    // Handle casting operations between different types
    @Test
    public void test_casting_operations() {
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{\"type\": \"MAP\", \"operators\": [{ \"type\": \"CAST\", \"fieldName\": \"age\", \"toType\": \"STRING\" }]}] }";
        AggregateRequest request = (AggregateRequest) RequestParser.parseRequest(message);
        assertNotNull(request);
        assertEquals(1, request.getAggregationSteps().size());
        BaseAggregationStep step = request.getAggregationSteps().getFirst();
        assertInstanceOf(MapAggregationStep.class, step);
        MapAggregationStep mapStep = (MapAggregationStep) step;
        assertEquals(1, mapStep.getOperators().size());
        MapOperator operator = mapStep.getOperators().getFirst();
        RemoveFieldMapOperator addFieldOperator = (RemoveFieldMapOperator) operator;
        assertEquals("age", addFieldOperator.getFieldName());
    }

    // Process empty or invalid aggregation steps
    @Test
    public void test_empty_invalid_aggregation_steps() {
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [] }";
        AggregateRequest request = (AggregateRequest) RequestParser.parseRequest(message);
        assertNotNull(request);
        assertTrue(request.getAggregationSteps().isEmpty());

        String invalidMessage = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{ \"type\": \"INVALID\" }] }";
        Exception exception = assertThrows(InvalidCommandException.class, () -> RequestParser.parseRequest(invalidMessage));
        assertNotNull(exception);
    }
}