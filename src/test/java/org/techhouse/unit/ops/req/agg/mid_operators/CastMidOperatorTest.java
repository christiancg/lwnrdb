package org.techhouse.unit.ops.req.agg.mid_operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastToType;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;

public class CastMidOperatorTest {
    @Test
    public void test_constructor_sets_cast_type() {
        String fieldName = "testField";
        CastToType toType = CastToType.STRING;

        CastMidOperator operator = new CastMidOperator(fieldName, toType);

        assertEquals(MidOperationType.CAST, operator.getType());
    }

    @Test
    public void test_constructor_accepts_null_field_name() {
        CastToType toType = CastToType.NUMBER;

        CastMidOperator operator = new CastMidOperator(null, toType);

        assertNull(operator.getFieldName());
        assertEquals(toType, operator.getToType());
    }

    @Test
    public void test_getters_and_setters() {
        String fieldName = "testField";
        CastToType toType = CastToType.STRING;

        CastMidOperator operator = new CastMidOperator(fieldName, toType);

        assertEquals("testField", operator.getFieldName());
        assertEquals(CastToType.STRING, operator.getToType());

        operator.setFieldName("newField");
        operator.setToType(CastToType.NUMBER);

        assertEquals("newField", operator.getFieldName());
        assertEquals(CastToType.NUMBER, operator.getToType());
    }
}
