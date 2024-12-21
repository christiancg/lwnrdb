package org.techhouse.unit.ops.req.agg.mid_operators;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastToType;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CastMidOperatorTest {
    // Constructor correctly sets MidOperationType.CAST as type using super()
    @Test
    public void test_constructor_sets_cast_type() {
        String fieldName = "testField";
        CastToType toType = CastToType.STRING;

        CastMidOperator operator = new CastMidOperator(fieldName, toType);

        assertEquals(MidOperationType.CAST, operator.getType());
    }

    // Constructor handles null fieldName parameter
    @Test
    public void test_constructor_accepts_null_field_name() {
        String fieldName = null;
        CastToType toType = CastToType.NUMBER;

        CastMidOperator operator = new CastMidOperator(fieldName, toType);

        assertNull(operator.getFieldName());
        assertEquals(toType, operator.getToType());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        String fieldName = "testField";
        CastToType toType = CastToType.STRING;

        CastMidOperator operator = new CastMidOperator(fieldName, toType);

        // Test getter methods
        assertEquals("testField", operator.getFieldName());
        assertEquals(CastToType.STRING, operator.getToType());

        // Test setter methods
        operator.setFieldName("newField");
        operator.setToType(CastToType.NUMBER);

        assertEquals("newField", operator.getFieldName());
        assertEquals(CastToType.NUMBER, operator.getToType());
    }
}