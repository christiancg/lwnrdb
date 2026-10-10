package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterOperatorCustomTypeTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_custom_in_returns_false() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(10:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.IN, "t", new JsonTime("#time(10:00:00)"));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.IN).test(obj, "t"));
    }

    @Test
    public void test_process_operator_with_custom_type_value() throws IOException {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("ct1"));
        obj.add("t", new JsonTime("#time(10:00:00)"));

        DbEntry entry = new DbEntry();
        entry.set_id("ct1");
        entry.setData(obj);
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        final var cache = IocContainer.get(Cache.class);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "ct1", 0, 100, 0));

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "t", new JsonTime("#time(10:00:00)"));
        List<JsonObject> result = FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertFalse(result.isEmpty());
    }

    @Test
    public void test_compare_custom_objects() {
        JsonCustom<?> customValue1 = new JsonTime("#time(10:00:00)");
        JsonCustom<?> customValue2 = new JsonTime("#time(10:00:00)");

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("customField", customValue2);

        FieldOperator operator = new FieldOperator(FieldOperatorType.EQUALS, "customField", customValue1);
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.EQUALS);

        assertTrue(tester.test(jsonObject, "customField"));
    }

    @Test
    public void test_custom_not_equals_returns_false_when_equal() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(10:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.NOT_EQUALS, "t", new JsonTime("#time(10:00:00)"));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.NOT_EQUALS).test(obj, "t"));
    }

    @Test
    public void test_custom_greater_than_returns_true() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(11:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.GREATER_THAN, "t", new JsonTime("#time(10:00:00)"));
        assertTrue(FilterOperatorHelper.getTester(op, FieldOperatorType.GREATER_THAN).test(obj, "t"));
    }

    @Test
    public void test_custom_smaller_than_returns_true() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(09:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.SMALLER_THAN, "t", new JsonTime("#time(10:00:00)"));
        assertTrue(FilterOperatorHelper.getTester(op, FieldOperatorType.SMALLER_THAN).test(obj, "t"));
    }

    @Test
    public void test_custom_greater_than_equals_at_boundary() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(10:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.GREATER_THAN_EQUALS, "t",
                new JsonTime("#time(10:00:00)"));
        assertTrue(FilterOperatorHelper.getTester(op, FieldOperatorType.GREATER_THAN_EQUALS).test(obj, "t"));
    }

    @Test
    public void test_custom_smaller_than_equals_at_boundary() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(10:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.SMALLER_THAN_EQUALS, "t",
                new JsonTime("#time(10:00:00)"));
        assertTrue(FilterOperatorHelper.getTester(op, FieldOperatorType.SMALLER_THAN_EQUALS).test(obj, "t"));
    }

    @Test
    public void test_custom_type_vs_string_field_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("t", "not_a_time");
        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "t", new JsonTime("#time(10:00:00)"));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.EQUALS).test(obj, "t"));
    }

    private static JsonArray arrayOf(JsonBaseElement element) {
        final var array = new JsonArray();
        array.add(element);
        return array;
    }

    private static JsonDateTime longSpelling() {
        return new JsonDateTime("#datetime(2024-01-01T10:00:00)");
    }

    private static JsonDateTime shortSpelling() {
        return new JsonDateTime("#datetime(2024-01-01T10:00)");
    }

    private static boolean matches(FieldOperatorType type, String field, JsonBaseElement operand,
            JsonBaseElement stored) {
        final var document = new JsonObject();
        document.add(field, stored);
        return FilterOperatorHelper.getTester(new FieldOperator(type, field, operand), type).test(document, field);
    }

    @Test
    public void test_equals_on_array_of_custom_values_matches_a_semantically_equal_spelling() {
        assertTrue(matches(FieldOperatorType.EQUALS, "times", arrayOf(shortSpelling()), arrayOf(longSpelling())));
    }

    @Test
    public void test_not_equals_on_array_of_custom_values_agrees_with_equals() {
        assertFalse(matches(FieldOperatorType.NOT_EQUALS, "times", arrayOf(shortSpelling()), arrayOf(longSpelling())));
        assertTrue(matches(FieldOperatorType.NOT_EQUALS, "times",
                arrayOf(new JsonDateTime("#datetime(2024-01-01T11:00)")), arrayOf(longSpelling())));
    }

    @Test
    public void test_contains_on_array_field_matches_a_semantically_equal_custom_operand() {
        assertTrue(matches(FieldOperatorType.CONTAINS, "times", shortSpelling(), arrayOf(longSpelling())));
    }

    @Test
    public void test_equals_on_object_containing_a_custom_value_matches_a_semantically_equal_spelling() {
        final var operand = new JsonObject();
        operand.add("when", shortSpelling());
        final var stored = new JsonObject();
        stored.add("when", longSpelling());
        assertTrue(matches(FieldOperatorType.EQUALS, "meta", operand, stored));
    }

    @Test
    public void test_in_over_arrays_matches_a_semantically_equal_custom_spelling() {
        assertTrue(matches(FieldOperatorType.IN, "times", arrayOf(arrayOf(shortSpelling())), arrayOf(longSpelling())));
    }

    @Test
    public void test_nested_strings_stay_case_sensitive_like_the_hash_index() {
        assertFalse(matches(FieldOperatorType.EQUALS, "tags", arrayOf(new JsonString("ABC")),
                arrayOf(new JsonString("abc"))));
        assertFalse(matches(FieldOperatorType.CONTAINS, "tags", new JsonString("ABC"), arrayOf(new JsonString("abc"))));
    }
}
