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
import org.techhouse.ejson.custom_types.JsonTime;
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

    // getTester: custom type IN returns false (L141 branch)
    @Test
    public void test_custom_in_returns_false() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(10:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.IN, "t", new JsonTime("#time(10:00:00)"));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.IN).test(obj, "t"));
    }

    // processOperator with a JsonCustom field value exercises the JsonCustom index path
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
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "ct1", 0, 100, 0));

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "t", new JsonTime("#time(10:00:00)"));
        List<JsonObject> result = FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertFalse(result.isEmpty());
    }

    // Compare custom objects using compareCustom method
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

    // getTester: custom type NOT_EQUALS returns false when values are equal
    @Test
    public void test_custom_not_equals_returns_false_when_equal() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(10:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.NOT_EQUALS, "t", new JsonTime("#time(10:00:00)"));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.NOT_EQUALS).test(obj, "t"));
    }

    // getTester: custom type GREATER_THAN returns true when field is after operator value
    @Test
    public void test_custom_greater_than_returns_true() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(11:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.GREATER_THAN, "t", new JsonTime("#time(10:00:00)"));
        assertTrue(FilterOperatorHelper.getTester(op, FieldOperatorType.GREATER_THAN).test(obj, "t"));
    }

    // getTester: custom type SMALLER_THAN returns true when field is before operator value
    @Test
    public void test_custom_smaller_than_returns_true() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(09:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.SMALLER_THAN, "t", new JsonTime("#time(10:00:00)"));
        assertTrue(FilterOperatorHelper.getTester(op, FieldOperatorType.SMALLER_THAN).test(obj, "t"));
    }

    // getTester: custom type GREATER_THAN_EQUALS returns true when field equals operator value
    @Test
    public void test_custom_greater_than_equals_at_boundary() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(10:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.GREATER_THAN_EQUALS, "t",
                new JsonTime("#time(10:00:00)"));
        assertTrue(FilterOperatorHelper.getTester(op, FieldOperatorType.GREATER_THAN_EQUALS).test(obj, "t"));
    }

    // getTester: custom type SMALLER_THAN_EQUALS returns true when field equals operator value
    @Test
    public void test_custom_smaller_than_equals_at_boundary() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(10:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.SMALLER_THAN_EQUALS, "t",
                new JsonTime("#time(10:00:00)"));
        assertTrue(FilterOperatorHelper.getTester(op, FieldOperatorType.SMALLER_THAN_EQUALS).test(obj, "t"));
    }

    // getTester: field value type mismatch with custom type returns false
    @Test
    public void test_custom_type_vs_string_field_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("t", "not_a_time");
        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "t", new JsonTime("#time(10:00:00)"));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.EQUALS).test(obj, "t"));
    }

}
