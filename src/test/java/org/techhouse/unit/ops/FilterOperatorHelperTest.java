package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.*;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class FilterOperatorHelperTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws InterruptedException, IOException, NoSuchFieldException, IllegalAccessException {
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
        BiPredicate<JsonObject, String> equalsTester = FilterOperatorHelper.getTester(equalsOp, FieldOperatorType.EQUALS);
        assertTrue(equalsTester.test(testObj1, "age"));
        assertFalse(equalsTester.test(testObj2, "age"));
    
        // Test not equals operator
        BiPredicate<JsonObject, String> notEqualsTester = FilterOperatorHelper.getTester(notEqualsOp, FieldOperatorType.NOT_EQUALS);
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
        final var adminCollPkIndexEntry = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100);
        cache.putAdminCollectionEntry(adminCollEntry, adminCollPkIndexEntry);
    
        // Test
        Stream<JsonObject> result = FilterOperatorHelper.processOperator(fieldOp, null, TestGlobals.DB, TestGlobals.COLL);
    
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
        Stream<JsonObject> result = FilterOperatorHelper.processOperator(conjunctionOp, resultStream, TestGlobals.DB, TestGlobals.COLL);

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
        Stream<JsonObject> processedStream = FilterOperatorHelper.processOperator(fieldOperator, resultStream, TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(processedStream);
        assertFalse(processedStream.findAny().isPresent());
    }

    // Successfully handle null resultStream for both operator types
    @Test
    public void test_handle_null_result_stream() throws IOException {
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of());
        FieldOperator fieldOperator = new FieldOperator(FieldOperatorType.EQUALS, "field", new JsonString("value"));

        Stream<JsonObject> processedConjunctionStream = FilterOperatorHelper.processOperator(conjunctionOperator, null, TestGlobals.DB, TestGlobals.COLL);
        Stream<JsonObject> processedFieldStream = FilterOperatorHelper.processOperator(fieldOperator, null, TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(processedConjunctionStream);
        assertNotNull(processedFieldStream);
    }

    // Return processed Stream<JsonObject> for valid inputs
    @Test
    public void test_return_processed_stream_for_valid_inputs() throws IOException {
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.OR, List.of());
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());
        Stream<JsonObject> processedStream = FilterOperatorHelper.processOperator(conjunctionOperator, resultStream, TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(processedStream);
        assertFalse(processedStream.findAny().isPresent());
    }

    // Process operators with valid dbName and collName parameters
    @Test
    public void test_process_operator_with_valid_db_and_coll() throws IOException {
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of());
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());

        Stream<JsonObject> processedStream = FilterOperatorHelper.processOperator(conjunctionOperator, resultStream, TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(processedStream);
    }

    // Handle null operator parameter
    @Test
    public void test_handle_null_operator_parameter() {
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());

        assertThrows(NullPointerException.class, () -> FilterOperatorHelper.processOperator(null, resultStream, TestGlobals.DB, TestGlobals.COLL));
    }

    // Handle null dbName or collName parameters
    @Test
    public void test_handle_null_db_or_coll_parameters() {
        FieldOperator fieldOperator = new FieldOperator(FieldOperatorType.EQUALS, "field", new JsonString("value"));
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());

        assertDoesNotThrow(() -> {
            FilterOperatorHelper.processOperator(fieldOperator, resultStream, null, "testCollection");
        });

        Stream<JsonObject> resultStream1 = Stream.of(new JsonObject());
        assertDoesNotThrow(() -> {
            FilterOperatorHelper.processOperator(fieldOperator, resultStream1, "testDb", null);
        });
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
        FieldOperator operator = new FieldOperator(FieldOperatorType.EQUALS, "stringField", new JsonString("testString"));
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.EQUALS);
        assertTrue(tester.test(jsonObject, "stringField"));

        operator = new FieldOperator(FieldOperatorType.NOT_EQUALS, "stringField", new JsonString("differentString"));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.NOT_EQUALS);
        assertTrue(tester.test(jsonObject, "stringField"));

        operator = new FieldOperator(FieldOperatorType.CONTAINS, "stringField", new JsonString("test"));
        tester = FilterOperatorHelper.getTester(operator, FieldOperatorType.CONTAINS);
        assertTrue(tester.test(jsonObject, "stringField"));
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
}