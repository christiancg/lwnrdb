package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterOperatorHelperTest {
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

    // Handle empty or null result streams
    @Test
    public void test_process_operator_null_stream() throws IOException {
        // Setup
        FieldOperator fieldOp = new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonString("value1"));

        JsonObject testData = new JsonObject();
        testData.addProperty("field1", "value1");

        DbEntry testEntry = new DbEntry();
        testEntry.setDatabaseName(TestGlobals.DB);
        testEntry.setCollectionName(TestGlobals.COLL);
        testEntry.setData(testData);
        testEntry.set_id("1");

        final var cache = IocContainer.get(Cache.class);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, testEntry);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, adminCollPkIndexEntry);

        // Test
        Stream<JsonObject> result = FilterOperatorHelper.processOperator(fieldOp, null, TestGlobals.DB,
                TestGlobals.COLL);

        // Verify
        assertNotNull(result);
        List<JsonObject> resultList = result.toList();
        assertEquals(1, resultList.size());
        assertEquals("value1", resultList.getFirst().get("field1").asJsonString().getValue());
    }

    // Process CONJUNCTION operator with valid ConjunctionOperator input and resultStream
    @Test
    public void test_process_conjunction_operator() throws IOException {
        // Arrange
        List<BaseOperator> operators = new ArrayList<>();
        operators.add(new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("test")));

        ConjunctionOperator conjunctionOp = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);

        JsonObject testObj = new JsonObject();
        testObj.addProperty(Globals.PK_FIELD, "test");
        testObj.addProperty("name", "test");
        Stream<JsonObject> resultStream = Stream.of(testObj);

        // Act
        Stream<JsonObject> result = FilterOperatorHelper.processOperator(conjunctionOp, resultStream, TestGlobals.DB,
                TestGlobals.COLL);

        // Assert
        List<JsonObject> resultList = result.toList();
        assertFalse(resultList.isEmpty());
        assertEquals("test", resultList.getFirst().get("name").asJsonString().getValue());
    }

    // Process FIELD operator with valid FieldOperator input and resultStream
    @Test
    public void test_process_field_operator_with_valid_input() throws IOException {
        FieldOperator fieldOperator = new FieldOperator(FieldOperatorType.EQUALS, "field", new JsonString("value"));
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());
        Stream<JsonObject> processedStream = FilterOperatorHelper.processOperator(fieldOperator, resultStream,
                TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(processedStream);
        assertFalse(processedStream.findAny().isPresent());
    }

    // Successfully handle null resultStream for both operator types
    @Test
    public void test_handle_null_result_stream() throws IOException {
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of());
        FieldOperator fieldOperator = new FieldOperator(FieldOperatorType.EQUALS, "field", new JsonString("value"));

        Stream<JsonObject> processedConjunctionStream = FilterOperatorHelper.processOperator(conjunctionOperator, null,
                TestGlobals.DB, TestGlobals.COLL);
        Stream<JsonObject> processedFieldStream = FilterOperatorHelper.processOperator(fieldOperator, null,
                TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(processedConjunctionStream);
        assertNotNull(processedFieldStream);
    }

    // Return processed Stream<JsonObject> for valid inputs
    @Test
    public void test_return_processed_stream_for_valid_inputs() throws IOException {
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.OR, List.of());
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());
        Stream<JsonObject> processedStream = FilterOperatorHelper.processOperator(conjunctionOperator, resultStream,
                TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(processedStream);
        assertFalse(processedStream.findAny().isPresent());
    }

    // Process operators with valid dbName and collName parameters
    @Test
    public void test_process_operator_with_valid_db_and_coll() throws IOException {
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of());
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());

        Stream<JsonObject> processedStream = FilterOperatorHelper.processOperator(conjunctionOperator, resultStream,
                TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(processedStream);
    }

    // Handle null operator parameter
    @Test
    public void test_handle_null_operator_parameter() {
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());

        assertThrows(NullPointerException.class,
                () -> FilterOperatorHelper.processOperator(null, resultStream, TestGlobals.DB, TestGlobals.COLL));
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

    // Handle null values and JsonNull instances
    @Test
    public void test_null_handling() {
        JsonObject testObj = new JsonObject();
        testObj.add("nullField", JsonNull.INSTANCE);

        FieldOperator operator = new FieldOperator(FieldOperatorType.EQUALS, "nullField", JsonNull.INSTANCE);
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.EQUALS);

        assertTrue(tester.test(testObj, "nullField"));

        testObj = new JsonObject();
        assertFalse(tester.test(testObj, "nonExistentField"));
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

    // getTester: type mismatch (field is string, operator expects number) returns false
    @Test
    public void test_type_mismatch_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("field", "notANumber");

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "field", new JsonNumber(42));
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(op, FieldOperatorType.EQUALS);

        assertFalse(tester.test(obj, "field"));
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

    // getTester: field does not exist in object returns false
    @Test
    public void test_missing_field_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("other", "value");

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "missing", new JsonString("value"));
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(op, FieldOperatorType.EQUALS);

        assertFalse(tester.test(obj, "missing"));
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

    // getTester: custom type IN returns false (L141 branch)
    @Test
    public void test_custom_in_returns_false() {
        JsonObject obj = new JsonObject();
        obj.add("t", new JsonTime("#time(10:00:00)"));
        FieldOperator op = new FieldOperator(FieldOperatorType.IN, "t", new JsonTime("#time(10:00:00)"));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.IN).test(obj, "t"));
    }

    // getTester: string GREATER_THAN returns false (L152 branch)
    @Test
    public void test_string_greater_than_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("s", "hello");
        FieldOperator op = new FieldOperator(FieldOperatorType.GREATER_THAN, "s", new JsonString("hello"));
        assertFalse(FilterOperatorHelper.getTester(op, FieldOperatorType.GREATER_THAN).test(obj, "s"));
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

    // processOperator uses index when available (covers matchingValues != null path L187-205)
    @Test
    public void test_process_operator_with_index_returns_indexed_results() throws Exception {
        final var cache = IocContainer.get(Cache.class);

        // Insert two entries
        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("idx1"));
        obj1.addProperty("score", 100);
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("idx2"));
        obj2.addProperty("score", 200);

        DbEntry e1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj1);
        e1.set_id("idx1");
        DbEntry e2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj2);
        e2.set_id("idx2");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, e1);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, e2);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "idx1", 0, 100, 0));

        // Create an index on "score"
        org.techhouse.ops.IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "score");
        // Ensure the collection entry knows about the index
        final var coll = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        coll.setIndexes(java.util.Set.of("score"));

        // Query with EQUALS on the indexed field — matchingValues will be non-null
        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "score", new JsonNumber(100));
        List<JsonObject> result = FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertEquals(1, result.size());
        assertEquals(100, result.getFirst().get("score").asJsonNumber().asInteger());
    }

    // matchingValues != null with non-null resultStream filters the existing stream by index (L194-205)
    @Test
    public void test_process_operator_with_index_and_existing_stream() throws Exception {
        final var cache = IocContainer.get(Cache.class);

        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("is1"));
        obj1.addProperty("level", 5);
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("is2"));
        obj2.addProperty("level", 10);

        DbEntry e1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj1);
        e1.set_id("is1");
        DbEntry e2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj2);
        e2.set_id("is2");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, e1);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, e2);
        final var adminCollEntry2 = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry2,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "is1", 0, 100, 0));

        org.techhouse.ops.IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "level");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(java.util.Set.of("level"));

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(5));
        // Pass a non-null stream — triggers the resultStream != null branch of matchingValues path
        java.util.stream.Stream<JsonObject> existing = java.util.stream.Stream.of(obj1, obj2);
        List<JsonObject> result = FilterOperatorHelper.processOperator(op, existing, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertEquals(1, result.size());
        assertEquals(5, result.getFirst().get("level").asJsonNumber().asInteger());
    }

    // processOperator with a JsonArray value exercises the JsonArray index path (IN)
    @Test
    public void test_process_operator_with_json_array_value() throws IOException {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("arr1"));
        obj.addProperty("color", "red");

        DbEntry entry = new DbEntry();
        entry.set_id("arr1");
        entry.setData(obj);
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        final var cache = IocContainer.get(Cache.class);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "arr1", 0, 100, 0));

        JsonArray arr = new JsonArray();
        arr.add(new JsonString("red"));
        arr.add(new JsonString("blue"));
        FieldOperator op = new FieldOperator(FieldOperatorType.IN, "color", arr);

        // With no index on "color", getIdsFromIndex returns null → falls through to full scan
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

    // processOperator filters an existing non-null stream using a field predicate
    @Test
    public void test_process_operator_filters_existing_stream() throws IOException {
        JsonObject match = new JsonObject();
        match.add(Globals.PK_FIELD, new JsonString("match"));
        match.addProperty("role", "admin");
        JsonObject noMatch = new JsonObject();
        noMatch.add(Globals.PK_FIELD, new JsonString("nomatch"));
        noMatch.addProperty("role", "user");

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "role", new JsonString("admin"));
        java.util.stream.Stream<JsonObject> existing = java.util.stream.Stream.of(match, noMatch);

        List<JsonObject> result = FilterOperatorHelper.processOperator(op, existing, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertEquals(1, result.size());
        assertEquals("admin", result.getFirst().get("role").asJsonString().getValue());
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

    // ---- resolveIdsViaIndex (index-only id-set resolution, used by index-backed COUNT) ----

    private void addIndexedDoc(Cache cache, String id, JsonBaseElement value) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("status", value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void index(Cache cache, String... fields) {
        for (final var field : fields) {
            org.techhouse.ops.IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        }
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(java.util.Set.of(fields));
    }

    // Populates the PK index in the user cache directly so NOR/NAND can compute the full id universe
    // without a real on-disk collection.
    private void injectPkIndex() throws Exception {
        final var userCache = IocContainer.get(org.techhouse.cache.UserCache.class);
        final var list = new ArrayList<PkIndexEntry>();
        for (final var id : new String[]{"r1", "r2", "r3"}) {
            list.add(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, id, 0, 100, 0));
        }
        final var pkMap = new java.util.concurrent.ConcurrentHashMap<String, List<PkIndexEntry>>();
        pkMap.put(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL), list);
        TestUtils.setPrivateField(userCache, "pkIndexMap", pkMap);
    }

    // A single indexed field operator resolves to exactly the matching ids.
    @Test
    public void test_resolve_ids_single_field_returns_index_ids() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addIndexedDoc(cache, "r1", new JsonString("active"));
        addIndexedDoc(cache, "r2", new JsonString("inactive"));
        addIndexedDoc(cache, "r3", new JsonString("active"));
        index(cache, "status");

        final var op = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"));
        final var ids = FilterOperatorHelper.resolveIdsViaIndex(op, TestGlobals.DB, TestGlobals.COLL);

        assertEquals(java.util.Set.of("r1", "r3"), ids);
    }

    // A field without an index cannot be resolved → null signals the caller to fall back.
    @Test
    public void test_resolve_ids_unindexed_field_returns_null() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addIndexedDoc(cache, "r1", new JsonString("active"));

        final var op = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"));
        assertNull(FilterOperatorHelper.resolveIdsViaIndex(op, TestGlobals.DB, TestGlobals.COLL));
    }

    // AND intersects the child id-sets.
    @Test
    public void test_resolve_ids_and_intersects_child_sets() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        addTwoFieldDoc(cache, "r2", "active", 2);
        addTwoFieldDoc(cache, "r3", "inactive", 1);
        index(cache, "status", "level");

        final var and = new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        assertEquals(java.util.Set.of("r1"),
                FilterOperatorHelper.resolveIdsViaIndex(and, TestGlobals.DB, TestGlobals.COLL));
    }

    // OR unions the child id-sets.
    @Test
    public void test_resolve_ids_or_unions_child_sets() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        addTwoFieldDoc(cache, "r2", "active", 2);
        addTwoFieldDoc(cache, "r3", "inactive", 3);
        index(cache, "status", "level");

        final var or = new ConjunctionOperator(ConjunctionOperatorType.OR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(3))));
        assertEquals(java.util.Set.of("r1", "r2", "r3"),
                FilterOperatorHelper.resolveIdsViaIndex(or, TestGlobals.DB, TestGlobals.COLL));
    }

    // XOR keeps ids that appear in exactly one child id-set.
    @Test
    public void test_resolve_ids_xor_keeps_exactly_one() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1); // matches both leaves
        addTwoFieldDoc(cache, "r2", "active", 2); // matches only status
        addTwoFieldDoc(cache, "r3", "inactive", 1); // matches only level
        index(cache, "status", "level");

        final var xor = new ConjunctionOperator(ConjunctionOperatorType.XOR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        assertEquals(java.util.Set.of("r2", "r3"),
                FilterOperatorHelper.resolveIdsViaIndex(xor, TestGlobals.DB, TestGlobals.COLL));
    }

    // NOR is the complement of the union against the full id universe (from the PK index).
    @Test
    public void test_resolve_ids_nor_complements_against_pk_index() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        addTwoFieldDoc(cache, "r2", "active", 2);
        addTwoFieldDoc(cache, "r3", "inactive", 3);
        index(cache, "status", "level");
        injectPkIndex();

        final var nor = new ConjunctionOperator(ConjunctionOperatorType.NOR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(3))));
        // union(active = r1,r2; level3 = r3) = {r1,r2,r3}; complement against {r1,r2,r3} = {}
        assertEquals(java.util.Set.of(),
                FilterOperatorHelper.resolveIdsViaIndex(nor, TestGlobals.DB, TestGlobals.COLL));
    }

    // NAND is the complement of the intersection against the full id universe.
    @Test
    public void test_resolve_ids_nand_complements_against_pk_index() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        addTwoFieldDoc(cache, "r2", "active", 2);
        addTwoFieldDoc(cache, "r3", "inactive", 1);
        index(cache, "status", "level");
        injectPkIndex();

        final var nand = new ConjunctionOperator(ConjunctionOperatorType.NAND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        // intersection(active = r1,r2; level1 = r1,r3) = {r1}; complement against {r1,r2,r3} = {r2,r3}
        assertEquals(java.util.Set.of("r2", "r3"),
                FilterOperatorHelper.resolveIdsViaIndex(nand, TestGlobals.DB, TestGlobals.COLL));
    }

    // A conjunction with one unindexed leaf cannot be resolved purely from indexes → null.
    @Test
    public void test_resolve_ids_conjunction_with_unindexed_leaf_returns_null() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        index(cache, "status"); // "level" is not indexed

        final var and = new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        assertNull(FilterOperatorHelper.resolveIdsViaIndex(and, TestGlobals.DB, TestGlobals.COLL));
    }

    // An indexed value with no matches resolves to an empty set (count 0), not null.
    @Test
    public void test_resolve_ids_no_matches_returns_empty_set() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addIndexedDoc(cache, "r1", new JsonString("active"));
        index(cache, "status");

        final var op = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("missing"));
        final var ids = FilterOperatorHelper.resolveIdsViaIndex(op, TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    private void addTwoFieldDoc(Cache cache, String id, String status, int level) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.addProperty("status", status);
        obj.addProperty("level", level);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }
}
