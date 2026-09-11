package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestUtils;

public class FilterComparisonOperatorTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Compare boolean values with EQUALS and NOT_EQUALS operators
    @Test
    public void test_boolean_comparison_operators() {
        JsonObject testObj = new JsonObject();
        testObj.add("boolField", new JsonBoolean(true));

        JsonBaseElement value = new JsonBoolean(true);
        FieldOperator operator = new FieldOperator(FieldOperatorType.EQUALS, "boolField", value);
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.EQUALS);

        assertTrue(tester.test(testObj, "boolField"));

        operator = new FieldOperator(FieldOperatorType.NOT_EQUALS, "boolField", new JsonBoolean(false));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.NOT_EQUALS);

        assertTrue(tester.test(testObj, "boolField"));
    }

    // Compare numeric values with all comparison operators
    @Test
    public void test_compare_numeric_values() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("numberField", 10);
        FieldOperator operator = new FieldOperator(FieldOperatorType.EQUALS, "numberField", new JsonNumber(10));
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.EQUALS);
        assertTrue(tester.test(jsonObject, "numberField"));

        operator = new FieldOperator(FieldOperatorType.NOT_EQUALS, "numberField", new JsonNumber(5));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.NOT_EQUALS);
        assertTrue(tester.test(jsonObject, "numberField"));

        operator = new FieldOperator(FieldOperatorType.GREATER_THAN, "numberField", new JsonNumber(5));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.GREATER_THAN);
        assertTrue(tester.test(jsonObject, "numberField"));

        operator = new FieldOperator(FieldOperatorType.GREATER_THAN_EQUALS, "numberField", new JsonNumber(10));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.GREATER_THAN_EQUALS);
        assertTrue(tester.test(jsonObject, "numberField"));

        operator = new FieldOperator(FieldOperatorType.SMALLER_THAN, "numberField", new JsonNumber(15));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.SMALLER_THAN);
        assertTrue(tester.test(jsonObject, "numberField"));

        operator = new FieldOperator(FieldOperatorType.SMALLER_THAN_EQUALS, "numberField", new JsonNumber(10));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.SMALLER_THAN_EQUALS);
        assertTrue(tester.test(jsonObject, "numberField"));
    }

    // Compare string values with EQUALS, NOT_EQUALS and CONTAINS operators
    @Test
    public void test_compare_string_values() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("stringField", "testString");
        FieldOperator operator = new FieldOperator(FieldOperatorType.EQUALS, "stringField",
                new JsonString("testString"));
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.EQUALS);
        assertTrue(tester.test(jsonObject, "stringField"));

        operator = new FieldOperator(FieldOperatorType.NOT_EQUALS, "stringField", new JsonString("differentString"));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.NOT_EQUALS);
        assertTrue(tester.test(jsonObject, "stringField"));

        operator = new FieldOperator(FieldOperatorType.CONTAINS, "stringField", new JsonString("test"));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.CONTAINS);
        assertTrue(tester.test(jsonObject, "stringField"));
    }

    // getTester: type mismatch (field is string, operator expects number) returns false
    @Test
    public void test_type_mismatch_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("field", "notANumber");

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "field", new JsonNumber(42));
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(op, FieldOperatorType.EQUALS);

        assertFalse(tester.test(obj, "field"));
    }

    // getTester: boolean with GREATER_THAN (unsupported) returns false (L117 branch)
    @Test
    public void test_boolean_greater_than_returns_false() {
        JsonObject obj = new JsonObject();
        obj.add("flag", new JsonBoolean(true));
        FieldOperator op = new FieldOperator(FieldOperatorType.GREATER_THAN, "flag", new JsonBoolean(true));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.GREATER_THAN).test(obj, "flag"));
    }

    // getTester: numeric IN returns false (L128 branch)
    @Test
    public void test_numeric_in_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("n", 5);
        FieldOperator op = new FieldOperator(FieldOperatorType.IN, "n", new JsonNumber(5));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.IN).test(obj, "n"));
    }

    // getTester: string GREATER_THAN returns false (L152 branch)
    @Test
    public void test_string_greater_than_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("s", "hello");
        FieldOperator op = new FieldOperator(FieldOperatorType.GREATER_THAN, "s", new JsonString("hello"));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.GREATER_THAN).test(obj, "s"));
    }

    // getTester: numeric GREATER_THAN returns false when value does not exceed threshold
    @Test
    public void test_numeric_greater_than_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("n", 5);
        FieldOperator op = new FieldOperator(FieldOperatorType.GREATER_THAN, "n", new JsonNumber(10));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.GREATER_THAN).test(obj, "n"));
    }

    // getTester: numeric SMALLER_THAN returns false when value is not below threshold
    @Test
    public void test_numeric_smaller_than_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("n", 15);
        FieldOperator op = new FieldOperator(FieldOperatorType.SMALLER_THAN, "n", new JsonNumber(10));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.SMALLER_THAN).test(obj, "n"));
    }

    // getTester: numeric GREATER_THAN_EQUALS returns false when value is below threshold
    @Test
    public void test_numeric_greater_than_equals_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("n", 5);
        FieldOperator op = new FieldOperator(FieldOperatorType.GREATER_THAN_EQUALS, "n", new JsonNumber(10));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.GREATER_THAN_EQUALS).test(obj, "n"));
    }

    // getTester: numeric SMALLER_THAN_EQUALS returns false when value exceeds threshold
    @Test
    public void test_numeric_smaller_than_equals_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("n", 15);
        FieldOperator op = new FieldOperator(FieldOperatorType.SMALLER_THAN_EQUALS, "n", new JsonNumber(10));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.SMALLER_THAN_EQUALS).test(obj, "n"));
    }
}
