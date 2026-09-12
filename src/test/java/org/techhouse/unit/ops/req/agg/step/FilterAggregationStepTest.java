package org.techhouse.unit.ops.req.agg.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;

public class FilterAggregationStepTest {
    @Test
    public void test_constructor_with_valid_operator() {
        BaseOperator operator = new BaseOperator(OperatorType.FIELD);

        FilterAggregationStep filterStep = new FilterAggregationStep(operator);

        assertEquals(AggregationStepType.FILTER, filterStep.getType());
        assertEquals(operator, filterStep.getOperator());
    }

    @Test
    public void test_constructor_with_null_operator() {
        FilterAggregationStep filterStep = new FilterAggregationStep(null);

        assertEquals(AggregationStepType.FILTER, filterStep.getType());
        assertNull(filterStep.getOperator());
    }

    @Test
    public void test_getter_and_setter_for_operator() {
        BaseOperator initialOperator = new BaseOperator(OperatorType.FIELD);
        FilterAggregationStep filterStep = new FilterAggregationStep(initialOperator);

        assertEquals(initialOperator, filterStep.getOperator());

        BaseOperator newOperator = new BaseOperator(OperatorType.CONJUNCTION);
        filterStep.setOperator(newOperator);
        assertEquals(newOperator, filterStep.getOperator());
    }
}
