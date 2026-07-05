package org.techhouse.unit.ops.req.agg.operators;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.operators.CustomOperator;

public class CustomOperatorTest {
    @Test
    public void test_constructor_sets_type_and_fields() {
        final var value = new JsonString("#geo(1,2)");
        final var args = new JsonObject();
        final var op = new CustomOperator("distance", "location", value, args);

        assertEquals(OperatorType.CUSTOM, op.getType());
        assertEquals("distance", op.getCustomOperatorName());
        assertEquals("location", op.getField());
        assertEquals(value, op.getValue());
        assertEquals(args, op.getArgs());
    }

    @Test
    public void test_setters() {
        final var op = new CustomOperator("distance", "location", null, new JsonObject());
        final var newValue = new JsonString("#geo(3,4)");
        final var newArgs = new JsonObject();

        op.setCustomOperatorName("within");
        op.setField("position");
        op.setValue(newValue);
        op.setArgs(newArgs);

        assertEquals("within", op.getCustomOperatorName());
        assertEquals("position", op.getField());
        assertEquals(newValue, op.getValue());
        assertEquals(newArgs, op.getArgs());
    }
}
