package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationDistinctStepTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Process distinct operation on specific field with valid data
    @Test
    public void test_process_distinct_operation_on_specific_field() throws IOException {
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        DistinctAggregationStep distinctStep = new DistinctAggregationStep("distinctField");
        request.setAggregationSteps(List.of(distinctStep));

        JsonObject jsonObject1 = new JsonObject();
        jsonObject1.addProperty("distinctField", "value1");
        JsonObject jsonObject2 = new JsonObject();
        jsonObject2.addProperty("distinctField", "value2");
        JsonObject jsonObject3 = new JsonObject();
        jsonObject3.addProperty("distinctField", "value1");

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertEquals(0, result.size());
    }

    // Helper to insert entries directly into cache and page metadata for the test collection
    private void insertEntry(Cache cache, String id, String fieldName, Object fieldValue) {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        if (fieldValue instanceof String s)
            obj.addProperty(fieldName, s);
        else if (fieldValue instanceof Number n)
            obj.addProperty(fieldName, n);
        DbEntry entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
    }

    // DISTINCT returns only unique values for the given field
    @Test
    public void test_distinct_returns_unique_values() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "d1", "color", "red");
        insertEntry(cache, "d2", "color", "blue");
        insertEntry(cache, "d3", "color", "red");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new DistinctAggregationStep("color")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(2, result.size());
    }

    // DISTINCT with null fieldName returns unique objects (removes _id) (L138-145)
    @Test
    public void test_distinct_with_null_field_name_returns_unique_objects() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "dn1", "color", "red");
        insertEntry(cache, "dn2", "color", "red");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new DistinctAggregationStep(null)));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);
        // null fieldName → distinct on whole object minus _id
        assertNotNull(result);
        // Both entries have same fields, so distinct should collapse to 1
        assertEquals(1, result.size());
    }

    // DISTINCT with empty fieldName also removes _id and deduplicates (L138-145)
    @Test
    public void test_distinct_with_empty_field_name() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "de1", "x", "same");
        insertEntry(cache, "de2", "x", "same");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new DistinctAggregationStep("")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    // ---- Index-backed aggregation steps (GROUP_BY, JOIN, SORT, DISTINCT) ----

    private void addDoc(Cache cache, String id, String field, JsonBaseElement value) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(field, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void enableIndex(Cache cache, String field) {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(field));
    }

    // DISTINCT over an indexed field returns the same values as a non-indexed scan
    @Test
    public void test_distinct_uses_index_returns_same_values_as_scan() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "color", new JsonString("red"));
        addDoc(cache, "c2", "color", new JsonString("blue"));
        addDoc(cache, "c3", "color", new JsonString("red"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var scanValues = AggregationOperationHelper.processAggregation(req).stream()
                .map(o -> o.get("color").asJsonString().getValue()).collect(java.util.stream.Collectors.toSet());

        enableIndex(cache, "color");
        final var indexed = AggregationOperationHelper.processAggregation(req);
        final var indexValues = indexed.stream().map(o -> o.get("color").asJsonString().getValue())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(2, indexed.size());
        assertEquals(scanValues, indexValues);
    }

    // Index-backed DISTINCT does not read any documents (still works after the doc cache is evicted)
    @Test
    public void test_distinct_indexed_reads_no_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "color", new JsonString("red"));
        addDoc(cache, "c2", "color", new JsonString("blue"));
        enableIndex(cache, "color");
        IocContainer.get(org.techhouse.cache.UserCache.class).evictCollectionDocuments(TestGlobals.DB,
                TestGlobals.COLL);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, result.size());
    }

    // DISTINCT with a null field name ignores the index and deduplicates whole documents
    @Test
    public void test_distinct_null_field_ignores_index() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "d1", "color", new JsonString("red"));
        addDoc(cache, "d2", "color", new JsonString("red"));
        enableIndex(cache, "color");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep(null)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(1, result.size());
    }

    // Without an index the step falls back to the scan path
    @Test
    public void test_distinct_without_index_falls_back_to_scan() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", "color", new JsonString("red"));
        addDoc(cache, "c2", "color", new JsonString("blue"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, result.size());
    }

    // Option B: documents whose indexed field holds an object/array are not in the index, so an
    // index-backed DISTINCT includes both scalar and object-valued docs on a mixed-type field
    @Test
    public void test_object_valued_field_included_in_indexed_distinct() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "o1", "data", new JsonString("scalar"));
        final var objVal = new JsonObject();
        objVal.addProperty("nested", 1);
        addDoc(cache, "o2", "data", objVal);
        enableIndex(cache, "data");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("data")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, result.size());
        final var hasScalar = result.stream().anyMatch(r -> r.get("data") != null && r.get("data").isJsonString());
        final var hasObject = result.stream().anyMatch(r -> r.get("data") != null && r.get("data").isJsonObject());
        assertTrue(hasScalar);
        assertTrue(hasObject);
    }

    // An indexed step on an empty collection returns no results
    @Test
    public void test_indexed_distinct_on_empty_collection_returns_empty() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        enableIndex(cache, "color");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(0, result.size());
    }

    // DISTINCT on a mixed scalar+object indexed field includes the object value in the result
    @Test
    public void test_distinct_mixed_type_indexed_field_includes_object_valued_docs() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "s1", "meta", new JsonString("plain"));
        final var obj = new JsonObject();
        obj.addProperty("key", "val");
        addDoc(cache, "o1", "meta", obj);
        enableIndex(cache, "meta");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("meta")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, result.size());
        final var hasObjectValue = result.stream().anyMatch(r -> r.get("meta") != null && r.get("meta").isJsonObject());
        assertTrue(hasObjectValue);
    }
}
