package org.techhouse.unit.ops.req.agg.mid_operators;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ArrayParamMidOperatorTest {
    // Create ArrayParamMidOperator with valid MidOperationType and non-empty JsonArray
    @Test
    public void test_create_with_valid_params() {
        JsonArray operands = new JsonArray();
        operands.add("test");
    
        ArrayParamMidOperator operator = new ArrayParamMidOperator(MidOperationType.SIZE, operands);
    
        assertEquals(MidOperationType.SIZE, operator.getType());
        assertEquals(operands, operator.getOperands());
        assertEquals(1, operator.getOperands().size());
    }

    // Create operator with null JsonArray operands
    @Test
    public void test_create_with_null_operands() {
        ArrayParamMidOperator operator = new ArrayParamMidOperator(MidOperationType.AVG, null);
    
        assertEquals(MidOperationType.AVG, operator.getType());
        assertNull(operator.getOperands());
    }

    // Test getters and setters for ArrayParamMidOperator with non-null operands
    @Test
    public void test_getters_and_setters_with_non_null_operands() {
        JsonArray jsonArray = new JsonArray();
        jsonArray.add("test");
        ArrayParamMidOperator operator = new ArrayParamMidOperator(MidOperationType.CONCAT, jsonArray);

        assertEquals(MidOperationType.CONCAT, operator.getType());
        assertEquals(jsonArray, operator.getOperands());

        JsonArray newJsonArray = new JsonArray();
        newJsonArray.add("newTest");
        operator.setOperands(newJsonArray);

        assertEquals(newJsonArray, operator.getOperands());
    }
}