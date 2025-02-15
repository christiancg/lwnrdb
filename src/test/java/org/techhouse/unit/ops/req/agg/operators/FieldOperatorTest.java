package org.techhouse.unit.ops.req.agg.operators;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class FieldOperatorTest {
    // Constructor initializes with valid FieldOperatorType, field name and JsonBaseElement value
    @Test
    public void test_constructor_initializes_with_valid_parameters() {
        FieldOperatorType operatorType = FieldOperatorType.EQUALS;
        String fieldName = "testField";
        JsonBaseElement value = new JsonString("testValue");

        FieldOperator operator = new FieldOperator(operatorType, fieldName, value);

        assertEquals(OperatorType.FIELD, operator.getType());
        assertEquals(operatorType, operator.getFieldOperatorType());
        assertEquals(fieldName, operator.getField());
        assertEquals(value, operator.getValue());
    }

    // Constructor handles null field name
    @Test
    public void test_constructor_accepts_null_field_name() {
        FieldOperatorType operatorType = FieldOperatorType.EQUALS;
        String fieldName = null;
        JsonBaseElement value = new JsonString("testValue");

        FieldOperator operator = new FieldOperator(operatorType, fieldName, value);

        assertEquals(OperatorType.FIELD, operator.getType());
        assertEquals(operatorType, operator.getFieldOperatorType());
        assertNull(operator.getField());
        assertEquals(value, operator.getValue());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        FieldOperatorType operatorType = FieldOperatorType.CONTAINS;
        String fieldName = "sampleField";
        JsonBaseElement value = new JsonString("sampleValue");

        FieldOperator operator = new FieldOperator(operatorType, fieldName, value);

        // Test getters
        assertEquals(operatorType, operator.getFieldOperatorType());
        assertEquals(fieldName, operator.getField());
        assertEquals(value, operator.getValue());

        // Test setters
        FieldOperatorType newOperatorType = FieldOperatorType.NOT_EQUALS;
        String newFieldName = "newField";
        JsonBaseElement newValue = new JsonString("newValue");

        operator.setFieldOperatorType(newOperatorType);
        operator.setField(newFieldName);
        operator.setValue(newValue);

        assertEquals(newOperatorType, operator.getFieldOperatorType());
        assertEquals(newFieldName, operator.getField());
        assertEquals(newValue, operator.getValue());
    }
}