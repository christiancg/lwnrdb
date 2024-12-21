package org.techhouse.unit.ops.req.agg.step;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.SortAggregationStep;

import static org.junit.jupiter.api.Assertions.*;

public class SortAggregationStepTest {
    // Constructor initializes with valid field name and ascending true
    @Test
    public void test_constructor_with_valid_params() {
        String fieldName = "name";
        boolean ascending = true;
    
        SortAggregationStep step = new SortAggregationStep(fieldName, ascending);
    
        assertEquals(AggregationStepType.SORT, step.getType());
        assertEquals(fieldName, step.getFieldName());
        assertEquals(ascending, step.getAscending());
    }

    // Constructor handles null field name
    @Test
    public void test_constructor_with_null_field_name() {
        String fieldName = null;
        boolean ascending = true;
    
        SortAggregationStep step = new SortAggregationStep(fieldName, ascending);
    
        assertEquals(AggregationStepType.SORT, step.getType());
        assertNull(step.getFieldName());
        assertEquals(ascending, step.getAscending());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        SortAggregationStep step = new SortAggregationStep("name", true);

        // Test initial values
        assertEquals("name", step.getFieldName());
        assertTrue(step.getAscending());

        // Test setters
        step.setFieldName("newName");
        step.setAscending(false);

        // Test updated values
        assertEquals("newName", step.getFieldName());
        assertFalse(step.getAscending());
    }
}