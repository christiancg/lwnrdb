package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;

public class RequestParserScriptOperatorTest {
    private static final String HEAD = "{\"type\":\"AGGREGATE\",\"databaseName\":\"testDB\","
            + "\"collectionName\":\"testCollection\",\"aggregationSteps\":[";

    private static AggregateRequest parse(String steps) {
        return (AggregateRequest) RequestParser.parseRequest(HEAD + steps + "]}");
    }

    @Test
    public void test_parses_a_script_filter_operator() {
        final var request = parse(
                "{\"type\":\"FILTER\",\"operator\":" + "{\"script\":\"export default (doc) => doc.price > 10;\"}}");
        final var step = (FilterAggregationStep) request.getAggregationSteps().getFirst();
        assertEquals(OperatorType.SCRIPT, step.getOperator().getType());
        assertEquals("export default (doc) => doc.price > 10;", ((ScriptOperator) step.getOperator()).getSource());
    }

    @Test
    public void test_parses_a_script_mid_operator() {
        final var request = parse("{\"type\":\"MAP\",\"operators\":[{\"fieldName\":\"total\","
                + "\"operator\":{\"type\":\"SCRIPT\",\"script\":\"export default (doc) => doc.price;\"}}]}");
        final var step = (MapAggregationStep) request.getAggregationSteps().getFirst();
        final var operator = ((AddFieldMapOperator) step.getOperators().getFirst()).getOperator();
        assertEquals(MidOperationType.SCRIPT, operator.getType());
        assertEquals("export default (doc) => doc.price;", ((ScriptMidOperator) operator).getSource());
    }

    @Test
    public void test_parses_a_reduce_step() {
        final var request = parse("{\"type\":\"REDUCE\",\"resultField\":\"total\",\"initialValue\":0,"
                + "\"script\":\"export default (acc, doc) => acc + doc.price;\"}");
        final var step = (ReduceAggregationStep) request.getAggregationSteps().getFirst();
        assertEquals(AggregationStepType.REDUCE, step.getType());
        assertEquals("total", step.getResultField());
        assertEquals(0d, step.getInitialValue().asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_reduce_step_without_initial_value_or_result_field() {
        final var request = parse("{\"type\":\"REDUCE\",\"script\":\"export default (acc, doc) => doc;\"}");
        final var step = (ReduceAggregationStep) request.getAggregationSteps().getFirst();
        assertEquals("value", step.getResultField());
        assertNull(step.getInitialValue());
    }

    @Test
    public void test_reduce_step_with_object_initial_value() {
        final var request = parse("{\"type\":\"REDUCE\",\"initialValue\":{\"n\":0},"
                + "\"script\":\"export default (acc, doc) => acc;\"}");
        final var step = (ReduceAggregationStep) request.getAggregationSteps().getFirst();
        assertEquals(0d, step.getInitialValue().asJsonObject().get("n").asJsonNumber().getValue().doubleValue());
    }

    // A malformed script operator would otherwise fall through to the conjunction branch and fail with a
    // message about conjunctionType, which says nothing about the actual mistake.
    @Test
    public void test_script_operator_is_not_misread_as_conjunction() {
        final var request = parse("{\"type\":\"FILTER\",\"operator\":{\"conjunctionType\":\"AND\",\"operators\":["
                + "{\"fieldOperatorType\":\"EQUALS\",\"field\":\"a\",\"value\":1},"
                + "{\"script\":\"export default (doc) => true;\"}]}}");
        final var step = (FilterAggregationStep) request.getAggregationSteps().getFirst();
        final var conjunction = (ConjunctionOperator) step.getOperator();
        assertEquals(OperatorType.SCRIPT, conjunction.getOperators().get(1).getType());
    }

    @Test
    public void test_map_condition_may_be_a_script() {
        final var request = parse("{\"type\":\"MAP\",\"operators\":[{\"fieldName\":\"total\","
                + "\"condition\":{\"script\":\"export default (doc) => true;\"},"
                + "\"operator\":{\"type\":\"SCRIPT\",\"script\":\"export default (doc) => 1;\"}}]}");
        final var step = (MapAggregationStep) request.getAggregationSteps().getFirst();
        assertEquals(OperatorType.SCRIPT, step.getOperators().getFirst().getCondition().getType());
    }
}
