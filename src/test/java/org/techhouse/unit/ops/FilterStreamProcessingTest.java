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
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNull;
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

public class FilterStreamProcessingTest {
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
    public void test_process_operator_null_stream() throws IOException {
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

        Stream<JsonObject> result = FilterOperatorHelper.processOperator(fieldOp, null, TestGlobals.DB,
                TestGlobals.COLL);

        assertNotNull(result);
        List<JsonObject> resultList = result.toList();
        assertEquals(1, resultList.size());
        assertEquals("value1", resultList.getFirst().get("field1").asJsonString().getValue());
    }

    @Test
    public void test_process_conjunction_operator() throws IOException {
        List<BaseOperator> operators = new ArrayList<>();
        operators.add(new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("test")));

        ConjunctionOperator conjunctionOp = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);

        JsonObject testObj = new JsonObject();
        testObj.addProperty(Globals.PK_FIELD, "test");
        testObj.addProperty("name", "test");
        Stream<JsonObject> resultStream = Stream.of(testObj);

        Stream<JsonObject> result = FilterOperatorHelper.processOperator(conjunctionOp, resultStream, TestGlobals.DB,
                TestGlobals.COLL);

        List<JsonObject> resultList = result.toList();
        assertFalse(resultList.isEmpty());
        assertEquals("test", resultList.getFirst().get("name").asJsonString().getValue());
    }

    @Test
    public void test_process_field_operator_with_valid_input() throws IOException {
        FieldOperator fieldOperator = new FieldOperator(FieldOperatorType.EQUALS, "field", new JsonString("value"));
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());
        Stream<JsonObject> processedStream = FilterOperatorHelper.processOperator(fieldOperator, resultStream,
                TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(processedStream);
        assertFalse(processedStream.findAny().isPresent());
    }

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

    @Test
    public void test_return_processed_stream_for_valid_inputs() throws IOException {
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.OR, List.of());
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());
        Stream<JsonObject> processedStream = FilterOperatorHelper.processOperator(conjunctionOperator, resultStream,
                TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(processedStream);
        assertFalse(processedStream.findAny().isPresent());
    }

    @Test
    public void test_process_operator_with_valid_db_and_coll() throws IOException {
        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of());
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());

        Stream<JsonObject> processedStream = FilterOperatorHelper.processOperator(conjunctionOperator, resultStream,
                TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(processedStream);
    }

    @Test
    public void test_handle_null_operator_parameter() {
        Stream<JsonObject> resultStream = Stream.of(new JsonObject());

        assertThrows(NullPointerException.class,
                () -> FilterOperatorHelper.processOperator(null, resultStream, TestGlobals.DB, TestGlobals.COLL));
    }

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

        List<JsonObject> result = FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertFalse(result.isEmpty());
    }

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

}
