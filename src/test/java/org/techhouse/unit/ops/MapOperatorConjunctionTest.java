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

    private static JsonObject documentWith(boolean field1, boolean field2, boolean field3) {
        final var document = new JsonObject();
        document.addProperty("field1", field1);
        document.addProperty("field2", field2);
        document.addProperty("field3", field3);
        document.addProperty("marker", "present");
        return document;
    }

    private static ConjunctionOperator conjunctionOf(ConjunctionOperatorType type) {
        final List<BaseOperator> leaves = List.of(
                new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonBoolean(true)),
                new FieldOperator(FieldOperatorType.EQUALS, "field2", new JsonBoolean(true)));
        return new ConjunctionOperator(type, leaves);
    }

    private static boolean conditionApplied(ConjunctionOperator condition, JsonObject document) {
        return !MapOperatorHelper.processOperator(new RemoveFieldMapOperator("marker", condition), document)
                .has("marker");
    }

    @Test
    public void test_nor_condition_applies_when_no_child_matches() {
        assertTrue(conditionApplied(conjunctionOf(ConjunctionOperatorType.NOR), documentWith(false, false, false)),
                "NOR must select a document matching none of its children");
    }

    @Test
    public void test_nor_condition_does_not_apply_when_one_child_matches() {
        assertFalse(conditionApplied(conjunctionOf(ConjunctionOperatorType.NOR), documentWith(true, false, false)),
                "NOR must reject a document matching any of its children");
    }

    @Test
    public void test_nand_condition_applies_when_not_every_child_matches() {
        assertTrue(conditionApplied(conjunctionOf(ConjunctionOperatorType.NAND), documentWith(true, false, false)),
                "NAND must select a document that does not match every child");
    }

    @Test
    public void test_nand_condition_does_not_apply_when_every_child_matches() {
        assertFalse(conditionApplied(conjunctionOf(ConjunctionOperatorType.NAND), documentWith(true, true, false)),
                "NAND must reject a document matching every child");
    }

    @Test
    public void test_nested_nor_inside_an_and_condition_is_evaluated() {
        final List<BaseOperator> nested = List.of(conjunctionOf(ConjunctionOperatorType.NOR),
                new FieldOperator(FieldOperatorType.EQUALS, "field3", new JsonBoolean(true)));
        final var outer = new ConjunctionOperator(ConjunctionOperatorType.AND, nested);

        assertTrue(conditionApplied(outer, documentWith(false, false, true)),
                "a nested NOR must contribute its real answer to the enclosing AND");
        assertFalse(conditionApplied(outer, documentWith(true, false, true)),
                "a nested NOR that rejects must make the enclosing AND reject too");
    }
}
