package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.MapOperatorHelper;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.test.TestUtils;

public class MapOperatorConjunctionTest {
    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    @Test
    public void test_empty_conjunction_operator_list() {
        JsonObject jsonObject = new JsonObject();
        List<BaseOperator> operators = new ArrayList<>();
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);
        RemoveFieldMapOperator mapOperator = new RemoveFieldMapOperator("nonExistentField", conjunctionOperator);

        JsonObject result = MapOperatorHelper.processOperator(mapOperator, jsonObject);

        assertTrue(result.isEmpty());
    }

    @Test
    public void test_recursive_conjunction_operator_processing() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", true);
        jsonObject.addProperty("field2", false);

        FieldOperator fieldOperator1 = new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonBoolean(true));
        FieldOperator fieldOperator2 = new FieldOperator(FieldOperatorType.EQUALS, "field2", new JsonBoolean(false));
        List<BaseOperator> operators = List.of(fieldOperator1, fieldOperator2);

        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);
        MapOperator mapOperator = new RemoveFieldMapOperator("field1", conjunctionOperator);

        JsonObject result = MapOperatorHelper.processOperator(mapOperator, jsonObject);

        assertFalse(result.has("field1"));
    }

    @Test
    public void test_operator_type_compatibility() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", 10);

        FieldOperator fieldOperator = new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonNumber(10));
        AddFieldMapOperator addFieldMapOperator = new AddFieldMapOperator("newField", fieldOperator, null);

        assertThrows(NullPointerException.class,
                () -> MapOperatorHelper.processOperator(addFieldMapOperator, jsonObject));
    }

    @Test
    public void test_and_conjunction_all_true() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("field1", 10);
        jsonObject.addProperty("field2", 20);
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("field1"));
        operands.add(new JsonString("field2"));
        operands.add(new JsonNumber(5));
        AddFieldMapOperator operator = getAddFieldMapOperator(operands);
        JsonObject result = MapOperatorHelper.processOperator(operator, jsonObject);
        assertEquals(35, result.get("result").asJsonNumber().asInteger());
    }

    private static @NonNull AddFieldMapOperator getAddFieldMapOperator(JsonArray operands) {
        ArrayParamMidOperator midOperator = new ArrayParamMidOperator(MidOperationType.SUM, operands);

        JsonObject conditionObject = new JsonObject();
        conditionObject.addProperty("field1", 10);
        conditionObject.addProperty("field2", 20);
        FieldOperator fieldOp1 = new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonNumber(10));
        FieldOperator fieldOp2 = new FieldOperator(FieldOperatorType.EQUALS, "field2", new JsonNumber(20));
        List<BaseOperator> operators = List.of(fieldOp1, fieldOp2);
        ConjunctionOperator conjunctionOp = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);

        return new AddFieldMapOperator("result", conjunctionOp, midOperator);
    }

    @Test
    public void test_nested_conjunction_as_condition() {
        JsonObject input = new JsonObject();
        input.addProperty("a", 1);
        input.addProperty("b", 2);

        ConjunctionOperator inner = new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "a", new JsonNumber(1)),
                        new FieldOperator(FieldOperatorType.EQUALS, "b", new JsonNumber(2))));
        ConjunctionOperator outer = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(inner));
        JsonArray operands = new JsonArray();
        operands.add(new JsonString("a"));
        AddFieldMapOperator op = new AddFieldMapOperator("result", outer,
                new ArrayParamMidOperator(MidOperationType.SUM, operands));

        JsonObject result = MapOperatorHelper.processOperator(op, input);
        assertTrue(result.has("result"));
    }
}
