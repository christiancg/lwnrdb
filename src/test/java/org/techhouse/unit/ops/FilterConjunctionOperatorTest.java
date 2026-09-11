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
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterConjunctionOperatorTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Process field operators with equals/not equals comparisons for primitive types
    @Test
    public void test_field_operator_equals_not_equals_primitives() {
        // Setup
        FieldOperator equalsOp = new FieldOperator(FieldOperatorType.EQUALS, "age", new JsonNumber(25));
        FieldOperator notEqualsOp = new FieldOperator(FieldOperatorType.NOT_EQUALS, "active", new JsonBoolean(true));

        JsonObject testObj1 = new JsonObject();
        testObj1.addProperty("age", 25);
        testObj1.addProperty("active", false);

        JsonObject testObj2 = new JsonObject();
        testObj2.addProperty("age", 30);
        testObj2.addProperty("active", true);

        // Test equals operator
        BiPredicate<JsonObject, String> equalsTester = FilterOperatorHelper.getTester(equalsOp,
                FieldOperatorType.EQUALS);
        assertTrue(equalsTester.test(testObj1, "age"));
        assertFalse(equalsTester.test(testObj2, "age"));

        // Test not equals operator
        BiPredicate<JsonObject, String> notEqualsTester = FilterOperatorHelper.getTester(notEqualsOp,
                FieldOperatorType.NOT_EQUALS);
        assertTrue(notEqualsTester.test(testObj1, "active"));
        assertFalse(notEqualsTester.test(testObj2, "active"));
    }

    // NOR conjunction returns only entries matching neither sub-operator
    @Test
    public void test_nor_conjunction_operator() throws IOException {
        JsonObject matchA = new JsonObject();
        matchA.add(Globals.PK_FIELD, new JsonString("a"));
        matchA.addProperty("name", "Alice");
        JsonObject matchB = new JsonObject();
        matchB.add(Globals.PK_FIELD, new JsonString("b"));
        matchB.addProperty("name", "Bob");
        JsonObject matchC = new JsonObject();
        matchC.add(Globals.PK_FIELD, new JsonString("c"));
        matchC.addProperty("name", "Charlie");

        final var cache = IocContainer.get(Cache.class);
        for (var obj : List.of(matchA, matchB, matchC)) {
            var entry = new org.techhouse.data.DbEntry();
            entry.setDatabaseName(TestGlobals.DB);
            entry.setCollectionName(TestGlobals.COLL);
            entry.set_id(obj.get(Globals.PK_FIELD).asJsonString().getValue());
            entry.setData(obj);
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        }
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "a", 0, 100, 0));

        ConjunctionOperator norOp = new ConjunctionOperator(ConjunctionOperatorType.NOR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("Alice")),
                        new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("Bob"))));

        List<JsonObject> result = FilterOperatorHelper.processOperator(norOp, null, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertEquals(1, result.size());
        assertEquals("Charlie", result.getFirst().get("name").asJsonString().getValue());
    }

    // NAND conjunction returns entries not matching all sub-operators simultaneously
    @Test
    public void test_nand_conjunction_operator() throws IOException {
        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("1"));
        obj1.addProperty("x", 10);
        obj1.addProperty("y", 20);
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("2"));
        obj2.addProperty("x", 10);
        obj2.addProperty("y", 99);

        final var cache = IocContainer.get(Cache.class);
        for (var obj : List.of(obj1, obj2)) {
            var entry = new org.techhouse.data.DbEntry();
            entry.setDatabaseName(TestGlobals.DB);
            entry.setCollectionName(TestGlobals.COLL);
            entry.set_id(obj.get(Globals.PK_FIELD).asJsonString().getValue());
            entry.setData(obj);
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        }
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100, 0));

        // NAND of (x==10 AND y==20) means: NOT(x==10 AND y==20) → obj2 matches, obj1 does not
        ConjunctionOperator nandOp = new ConjunctionOperator(ConjunctionOperatorType.NAND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "x", new JsonNumber(10)),
                        new FieldOperator(FieldOperatorType.EQUALS, "y", new JsonNumber(20))));

        List<JsonObject> result = FilterOperatorHelper.processOperator(nandOp, null, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertEquals(1, result.size());
        assertEquals("2", result.getFirst().get(Globals.PK_FIELD).asJsonString().getValue());
    }

    // XOR conjunction returns entries matching exactly one sub-operator
    @Test
    public void test_xor_conjunction_operator() throws IOException {
        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("1"));
        obj1.addProperty("a", true);
        obj1.addProperty("b", false);
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("2"));
        obj2.addProperty("a", true);
        obj2.addProperty("b", true);

        final var cache = IocContainer.get(Cache.class);
        for (var obj : List.of(obj1, obj2)) {
            var entry = new org.techhouse.data.DbEntry();
            entry.setDatabaseName(TestGlobals.DB);
            entry.setCollectionName(TestGlobals.COLL);
            entry.set_id(obj.get(Globals.PK_FIELD).asJsonString().getValue());
            entry.setData(obj);
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        }
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100, 0));

        // XOR of (a==true, b==true): obj1 matches only a==true (1 match) → included; obj2 matches both → excluded
        ConjunctionOperator xorOp = new ConjunctionOperator(ConjunctionOperatorType.XOR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "a", new JsonBoolean(true)),
                        new FieldOperator(FieldOperatorType.EQUALS, "b", new JsonBoolean(true))));

        List<JsonObject> result = FilterOperatorHelper.processOperator(xorOp, null, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertEquals(1, result.size());
        assertEquals("1", result.getFirst().get(Globals.PK_FIELD).asJsonString().getValue());
    }

    // getTester: IN operator with JsonArray matches when value is in the array
    @Test
    public void test_in_operator_tester_matches() {
        JsonObject obj = new JsonObject();
        obj.addProperty("color", "red");

        JsonArray arr = new JsonArray();
        arr.add(new JsonString("red"));
        arr.add(new JsonString("blue"));
        FieldOperator op = new FieldOperator(FieldOperatorType.IN, "color", arr);
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(op, FieldOperatorType.IN);

        assertTrue(tester.test(obj, "color"));
    }

    // getTester: IN operator with JsonArray returns false when value is not in the array
    @Test
    public void test_in_operator_tester_no_match() {
        JsonObject obj = new JsonObject();
        obj.addProperty("color", "green");

        JsonArray arr = new JsonArray();
        arr.add(new JsonString("red"));
        arr.add(new JsonString("blue"));
        FieldOperator op = new FieldOperator(FieldOperatorType.IN, "color", arr);
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(op, FieldOperatorType.IN);

        assertFalse(tester.test(obj, "color"));
    }

    // getTester: NOT_IN operator with JsonArray returns true when value is not in the array
    @Test
    public void test_not_in_operator_tester() {
        JsonObject obj = new JsonObject();
        obj.addProperty("color", "green");

        JsonArray arr = new JsonArray();
        arr.add(new JsonString("red"));
        arr.add(new JsonString("blue"));
        FieldOperator op = new FieldOperator(FieldOperatorType.NOT_IN, "color", arr);
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(op, FieldOperatorType.NOT_IN);

        assertTrue(tester.test(obj, "color"));
    }

    // Nested conjunction operator (conjunction within conjunction) is processed recursively
    @Test
    public void test_nested_conjunction_operators() throws IOException {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("nested1"));
        obj.addProperty("a", "x");
        obj.addProperty("b", "y");

        DbEntry entry = new DbEntry();
        entry.set_id("nested1");
        entry.setData(obj);
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        final var cache = IocContainer.get(Cache.class);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "nested1", 0, 100, 0));

        FieldOperator opA = new FieldOperator(FieldOperatorType.EQUALS, "a", new JsonString("x"));
        FieldOperator opB = new FieldOperator(FieldOperatorType.EQUALS, "b", new JsonString("y"));
        ConjunctionOperator inner = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(opA, opB));
        ConjunctionOperator outer = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(inner));

        // Pass null stream so each sub-operator loads from cache independently
        List<JsonObject> result = FilterOperatorHelper.processOperator(outer, null, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertEquals(1, result.size());
    }

    private static JsonObject objField(int n) {
        final var inner = new JsonObject();
        inner.addProperty("n", n);
        return inner;
    }

    private static JsonArray arrField(String... items) {
        final var arr = new JsonArray();
        for (var item : items) {
            arr.add(new JsonString(item));
        }
        return arr;
    }

    // getTester: whole-object EQUALS / NOT_EQUALS on the scan path (previously always returned false)
    @Test
    public void test_object_equals_not_equals_tester() {
        FieldOperator equalsOp = new FieldOperator(FieldOperatorType.EQUALS, "data", objField(1));
        JsonObject match = new JsonObject();
        match.add("data", objField(1));
        JsonObject noMatch = new JsonObject();
        noMatch.add("data", objField(2));
        JsonObject scalar = new JsonObject();
        scalar.addProperty("data", "x");

        BiPredicate<JsonObject, String> equalsTester = FilterOperatorHelper.getTester(equalsOp,
                FieldOperatorType.EQUALS);
        assertTrue(equalsTester.test(match, "data"));
        assertFalse(equalsTester.test(noMatch, "data"));
        assertFalse(equalsTester.test(scalar, "data"));

        FieldOperator notEqualsOp = new FieldOperator(FieldOperatorType.NOT_EQUALS, "data", objField(1));
        BiPredicate<JsonObject, String> notEqualsTester = FilterOperatorHelper.getTester(notEqualsOp,
                FieldOperatorType.NOT_EQUALS);
        assertTrue(notEqualsTester.test(noMatch, "data"));
        assertFalse(notEqualsTester.test(match, "data"));
    }

    // getTester: whole-array EQUALS / NOT_EQUALS on the scan path (order sensitive)
    @Test
    public void test_array_equals_not_equals_tester() {
        FieldOperator equalsOp = new FieldOperator(FieldOperatorType.EQUALS, "data", arrField("x", "y"));
        JsonObject match = new JsonObject();
        match.add("data", arrField("x", "y"));
        JsonObject reordered = new JsonObject();
        reordered.add("data", arrField("y", "x"));

        BiPredicate<JsonObject, String> equalsTester = FilterOperatorHelper.getTester(equalsOp,
                FieldOperatorType.EQUALS);
        assertTrue(equalsTester.test(match, "data"));
        assertFalse(equalsTester.test(reordered, "data"));

        FieldOperator notEqualsOp = new FieldOperator(FieldOperatorType.NOT_EQUALS, "data", arrField("x", "y"));
        BiPredicate<JsonObject, String> notEqualsTester = FilterOperatorHelper.getTester(notEqualsOp,
                FieldOperatorType.NOT_EQUALS);
        assertTrue(notEqualsTester.test(reordered, "data"));
        assertFalse(notEqualsTester.test(match, "data"));
    }

    // getTester: IN over a list of objects matches an object-valued field by element equality
    // (scan path, no index) — must agree with the index-backed resolution
    @Test
    public void test_in_operator_tester_matches_object_and_array_members() {
        JsonArray objectList = new JsonArray();
        objectList.add(objField(1));
        objectList.add(objField(2));
        FieldOperator inOp = new FieldOperator(FieldOperatorType.IN, "data", objectList);
        BiPredicate<JsonObject, String> inTester = FilterOperatorHelper.getTester(inOp, FieldOperatorType.IN);
        JsonObject inDoc = new JsonObject();
        inDoc.add("data", objField(1));
        JsonObject outDoc = new JsonObject();
        outDoc.add("data", objField(9));
        assertTrue(inTester.test(inDoc, "data"));
        assertFalse(inTester.test(outDoc, "data"));

        FieldOperator notInOp = new FieldOperator(FieldOperatorType.NOT_IN, "data", objectList);
        BiPredicate<JsonObject, String> notInTester = FilterOperatorHelper.getTester(notInOp, FieldOperatorType.NOT_IN);
        assertFalse(notInTester.test(inDoc, "data"));
        assertTrue(notInTester.test(outDoc, "data"));

        // arrays as candidates work the same way
        JsonArray arrayList = new JsonArray();
        arrayList.add(arrField("x", "y"));
        FieldOperator arrInOp = new FieldOperator(FieldOperatorType.IN, "data", arrayList);
        JsonObject arrDoc = new JsonObject();
        arrDoc.add("data", arrField("x", "y"));
        assertTrue(FilterOperatorHelper.getTester(arrInOp, FieldOperatorType.IN).test(arrDoc, "data"));
    }
}
