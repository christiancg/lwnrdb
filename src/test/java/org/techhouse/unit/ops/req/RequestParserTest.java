package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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
                    "databaseName": "newDb"
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

    // Successfully parse LIST_DATABASES request
    @Test
    public void test_parse_list_databases_request() {
        String listDatabasesRequest = """
                {
                    "type": "LIST_DATABASES"
                }""";

        OperationRequest result = RequestParser.parseRequest(listDatabasesRequest);

        assertInstanceOf(ListDatabasesRequest.class, result);
        assertEquals(OperationType.LIST_DATABASES, result.getType());
        assertNull(result.getDatabaseName());
        assertNull(result.getCollectionName());
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
        Exception exception = assertThrows(InvalidCommandException.class,
                () -> RequestParser.parseRequest(invalidMessage));
        assertNotNull(exception);
    }

    // Successfully parse LIST_COLLECTIONS request
    @Test
    public void test_parse_list_collections_request() {
        String listCollectionsRequest = """
                {
                    "type": "LIST_COLLECTIONS",
                    "databaseName": "testDb"
                }""";

        OperationRequest result = RequestParser.parseRequest(listCollectionsRequest);

        assertInstanceOf(ListCollectionsRequest.class, result);
        assertEquals(OperationType.LIST_COLLECTIONS, result.getType());
        assertEquals("testDb", result.getDatabaseName());
        assertNull(result.getCollectionName());
    }

    // Parse BULK_SAVE request
    @Test
    public void test_parse_bulk_save_request() {
        String msg = """
                {"type":"BULK_SAVE","databaseName":"db","collectionName":"coll","objects":[{"name":"a"}]}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(BulkSaveRequest.class, result);
        assertEquals(OperationType.BULK_SAVE, result.getType());
    }

    // Parse FIND_BY_ID request
    @Test
    public void test_parse_find_by_id_request() {
        String msg = """
                {"type":"FIND_BY_ID","databaseName":"db","collectionName":"coll","_id":"123"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(FindByIdRequest.class, result);
        assertEquals(OperationType.FIND_BY_ID, result.getType());
    }

    // Parse DROP_DATABASE request
    @Test
    public void test_parse_drop_database_request() {
        String msg = """
                {"type":"DROP_DATABASE","databaseName":"myDb"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(DropDatabaseRequest.class, result);
        assertEquals(OperationType.DROP_DATABASE, result.getType());
        assertEquals("myDb", result.getDatabaseName());
    }

    // Parse DROP_COLLECTION request
    @Test
    public void test_parse_drop_collection_request() {
        String msg = """
                {"type":"DROP_COLLECTION","databaseName":"db","collectionName":"coll"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(DropCollectionRequest.class, result);
        assertEquals(OperationType.DROP_COLLECTION, result.getType());
    }

    // Parse CREATE_INDEX request
    @Test
    public void test_parse_create_index_request() {
        String msg = """
                {"type":"CREATE_INDEX","databaseName":"db","collectionName":"coll","fieldName":"myField"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CreateIndexRequest.class, result);
        assertEquals(OperationType.CREATE_INDEX, result.getType());
    }

    // Parse DROP_INDEX request
    @Test
    public void test_parse_drop_index_request() {
        String msg = """
                {"type":"DROP_INDEX","databaseName":"db","collectionName":"coll","fieldName":"myField"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(DropIndexRequest.class, result);
        assertEquals(OperationType.DROP_INDEX, result.getType());
    }

    // Parse CLOSE_CONNECTION request
    @Test
    public void test_parse_close_connection_request() {
        String msg = """
                {"type":"CLOSE_CONNECTION"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CloseConnectionRequest.class, result);
        assertEquals(OperationType.CLOSE_CONNECTION, result.getType());
    }

    // Parse CREATE_COLLECTION request (covers L41)
    @Test
    public void test_parse_create_collection_request() {
        String msg = """
                {"type":"CREATE_COLLECTION","databaseName":"db","collectionName":"coll"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CreateCollectionRequest.class, result);
        assertEquals(OperationType.CREATE_COLLECTION, result.getType());
    }

    // RequestParser instantiation covers implicit default constructor (L20)
    @Test
    public void test_request_parser_instantiation() {
        assertNotNull(new RequestParser());
    }

    // MAP step with a conjunction condition covers the condition parsing path (L94, L137-138)
    @Test
    public void test_parse_map_step_with_conjunction_condition() {
        String msg = """
                {"type":"AGGREGATE","databaseName":"db","collectionName":"coll","aggregationSteps":[
                  {"type":"MAP","operators":[{
                    "fieldName":"result",
                    "condition":{"conjunctionType":"AND","operators":[
                      {"fieldOperatorType":"EQUALS","field":"x","value":1}
                    ]},
                    "operator":{"type":"SUM","operands":["x",1]}
                  }]}
                ]}""";
        AggregateRequest result = (AggregateRequest) RequestParser.parseRequest(msg);
        assertNotNull(result);
        assertEquals(1, result.getAggregationSteps().size());
    }

    // Parse aggregation with GROUP_BY, COUNT, DISTINCT, JOIN, LIMIT, SKIP, SORT steps
    @Test
    public void test_parse_aggregation_with_all_step_types() {
        String msg = """
                {"type":"AGGREGATE","databaseName":"db","collectionName":"coll","aggregationSteps":[
                  {"type":"GROUP_BY","fieldName":"category"},
                  {"type":"COUNT"},
                  {"type":"DISTINCT","fieldName":"name"},
                  {"type":"JOIN","joinCollection":"other","localField":"id","remoteField":"refId","asField":"joined"},
                  {"type":"LIMIT","limit":10},
                  {"type":"SKIP","skip":5},
                  {"type":"SORT","fieldName":"score","ascending":true}
                ]}""";
        AggregateRequest result = (AggregateRequest) RequestParser.parseRequest(msg);
        assertNotNull(result);
        assertEquals(7, result.getAggregationSteps().size());
    }

    // Parse aggregation MAP step with ABS mid-operator
    @Test
    public void test_parse_map_with_abs_operator() {
        String msg = """
                {"type":"AGGREGATE","databaseName":"db","collectionName":"coll","aggregationSteps":[
                  {"type":"MAP","operators":[{"fieldName":"absVal","condition":null,"operator":{"type":"ABS","operand":"n"}}]}
                ]}""";
        AggregateRequest result = (AggregateRequest) RequestParser.parseRequest(msg);
        assertNotNull(result);
        assertEquals(1, result.getAggregationSteps().size());
    }

    // Parse aggregation MAP step with CAST mid-operator
    @Test
    public void test_parse_map_with_cast_operator() {
        String msg = """
                {"type":"AGGREGATE","databaseName":"db","collectionName":"coll","aggregationSteps":[
                  {"type":"MAP","operators":[{"fieldName":"casted","condition":null,"operator":{"type":"CAST","fieldName":"score","toType":"STRING"}}]}
                ]}""";
        AggregateRequest result = (AggregateRequest) RequestParser.parseRequest(msg);
        assertNotNull(result);
        assertEquals(1, result.getAggregationSteps().size());
    }
}
