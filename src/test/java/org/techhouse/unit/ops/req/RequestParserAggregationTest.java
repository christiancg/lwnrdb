package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;

public class RequestParserAggregationTest {
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

    // Parse a FILTER step whose operator is a geo "distance" custom operator.
    @Test
    public void test_parse_aggregation_request_with_custom_distance_operator() {
        new org.techhouse.ejson.EJson(); // ensure the geo custom type is registered
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{ \"type\": \"FILTER\", \"operator\": { \"customOperatorName\": \"distance\", \"field\": \"location\", \"value\": \"#geo(40.71,-74.0)\", \"comparator\": \"SMALLER_THAN\", \"distance\": 1000 } }] }";
        OperationRequest request = RequestParser.parseRequest(message);
        AggregateRequest aggRequest = (AggregateRequest) request;
        FilterAggregationStep filterStep = (FilterAggregationStep) aggRequest.getAggregationSteps().getFirst();
        assertInstanceOf(CustomOperator.class, filterStep.getOperator());
        CustomOperator customOperator = (CustomOperator) filterStep.getOperator();
        assertEquals("distance", customOperator.getCustomOperatorName());
        assertEquals("location", customOperator.getField());
        assertTrue(customOperator.getValue().isJsonCustom());
        assertEquals("SMALLER_THAN", customOperator.getArgs().get("comparator").asJsonString().getValue());
    }

    // A custom operator nested inside a conjunction is parsed recursively.
    @Test
    public void test_parse_custom_operator_inside_conjunction() {
        new org.techhouse.ejson.EJson();
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{ \"type\": \"FILTER\", \"operator\": { \"conjunctionType\": \"AND\", \"operators\": [{ \"customOperatorName\": \"within\", \"field\": \"location\", \"polygon\": [\"#geo(0,0)\", \"#geo(0,1)\", \"#geo(1,1)\"] }] } }] }";
        OperationRequest request = RequestParser.parseRequest(message);
        AggregateRequest aggRequest = (AggregateRequest) request;
        FilterAggregationStep filterStep = (FilterAggregationStep) aggRequest.getAggregationSteps().getFirst();
        ConjunctionOperator conjunction = (ConjunctionOperator) filterStep.getOperator();
        assertInstanceOf(CustomOperator.class, conjunction.getOperators().getFirst());
        assertEquals("within", ((CustomOperator) conjunction.getOperators().getFirst()).getCustomOperatorName());
    }

    // Parse aggregation request with analyze flag set to true
    @Test
    public void test_parse_aggregate_with_analyze_true() {
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"analyze\": true, \"aggregationSteps\": [] }";
        OperationRequest request = RequestParser.parseRequest(message);
        assertInstanceOf(AggregateRequest.class, request);
        assertTrue(((AggregateRequest) request).isAnalyze());
    }

    // The analyze flag defaults to false when omitted
    @Test
    public void test_parse_aggregate_analyze_defaults_false() {
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [] }";
        OperationRequest request = RequestParser.parseRequest(message);
        assertInstanceOf(AggregateRequest.class, request);
        assertFalse(((AggregateRequest) request).isAnalyze());
    }

    // Parse map operations with add field and remove field operators
    @Test
    public void test_parse_map_operations_with_add_and_remove_field_operators() {
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{ \"type\": \"MAP\", \"operators\": [{ \"fieldName\": \"newField\", \"operator\": { \"type\": \"SUM\", \"operands\": [\"a_field\", 30] }}] }] }";
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
        assertEquals("a_field", addParamOperator.getOperands().asList().getFirst().asJsonString().getValue());
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

    // Parse mid-operators with array parameters (AVG, SUM, etc.)
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
