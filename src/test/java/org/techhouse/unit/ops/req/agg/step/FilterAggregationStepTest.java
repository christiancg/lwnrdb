package org.techhouse.unit.ops.req.agg.step;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class FilterAggregationStepTest {
    // Constructor initializes FilterAggregationStep with valid BaseOperator
    @Test
    public void test_constructor_with_valid_operator() {
        BaseOperator operator = new BaseOperator(OperatorType.FIELD);
    
        FilterAggregationStep filterStep = new FilterAggregationStep(operator);
    
        assertEquals(AggregationStepType.FILTER, filterStep.getType());
        assertEquals(operator, filterStep.getOperator());
    }

    // Constructor called with null operator
    @Test
    public void test_constructor_with_null_operator() {
        FilterAggregationStep filterStep = new FilterAggregationStep(null);
    
        assertEquals(AggregationStepType.FILTER, filterStep.getType());
        assertNull(filterStep.getOperator());
    }

    // create tests for getters and setters provided by lombok
    @Test
    public void test_getter_and_setter_for_operator() {
        BaseOperator initialOperator = new BaseOperator(OperatorType.FIELD);
        FilterAggregationStep filterStep = new FilterAggregationStep(initialOperator);

        // Test getter
        assertEquals(initialOperator, filterStep.getOperator());

        // Test setter
        BaseOperator newOperator = new BaseOperator(OperatorType.CONJUNCTION);
        filterStep.setOperator(newOperator);
        assertEquals(newOperator, filterStep.getOperator());
    }
}