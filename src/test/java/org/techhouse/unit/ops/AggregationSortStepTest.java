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
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationSortStepTest {
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

    // Helper to insert entries directly into cache and page metadata for the test collection
    private void insertEntry(Cache cache, String id, Object fieldValue) {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        if (fieldValue instanceof String s)
            obj.addProperty("score", s);
        else if (fieldValue instanceof Number n)
            obj.addProperty("score", n);
        DbEntry entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
    }

    // SORT ascending orders documents by the given field
    @Test
    public void test_sort_ascending_orders_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "s1", 30);
        insertEntry(cache, "s2", 10);
        insertEntry(cache, "s3", 20);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep("score", true)));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(3, result.size());
        assertEquals(10, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(20, result.get(1).get("score").asJsonNumber().asInteger());
        assertEquals(30, result.get(2).get("score").asJsonNumber().asInteger());
    }

    // SORT descending orders documents by the given field in reverse
    @Test
    public void test_sort_descending_orders_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "sd1", 30);
        insertEntry(cache, "sd2", 10);
        insertEntry(cache, "sd3", 20);

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep("score", false)));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(3, result.size());
        assertEquals(30, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(20, result.get(1).get("score").asJsonNumber().asInteger());
        assertEquals(10, result.get(2).get("score").asJsonNumber().asInteger());
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

    // SORT ascending over an indexed field orders documents like the non-indexed path
    @Test
    public void test_sort_ascending_uses_index_matches_scan_order() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "s1", "score", new JsonNumber(30));
        addDoc(cache, "s2", "score", new JsonNumber(10));
        addDoc(cache, "s3", "score", new JsonNumber(20));
        enableIndex(cache, "score");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new SortAggregationStep("score", true)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(3, result.size());
        assertEquals(10, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(20, result.get(1).get("score").asJsonNumber().asInteger());
        assertEquals(30, result.get(2).get("score").asJsonNumber().asInteger());
    }

    // SORT descending over an indexed field orders documents in reverse
    @Test
    public void test_sort_descending_uses_index_matches_scan_order() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "s1", "score", new JsonNumber(30));
        addDoc(cache, "s2", "score", new JsonNumber(10));
        addDoc(cache, "s3", "score", new JsonNumber(20));
        enableIndex(cache, "score");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new SortAggregationStep("score", false)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(3, result.size());
        assertEquals(30, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(20, result.get(1).get("score").asJsonNumber().asInteger());
        assertEquals(10, result.get(2).get("score").asJsonNumber().asInteger());
    }

    // SORT after a FILTER must sort only the filtered subset (no index fast-path)
    @Test
    public void test_sort_with_upstream_stream_does_not_use_index() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "s1", "score", new JsonNumber(30));
        addDoc(cache, "s2", "score", new JsonNumber(10));
        addDoc(cache, "s3", "score", new JsonNumber(20));
        enableIndex(cache, "score");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.GREATER_THAN, "score", new JsonNumber(10))),
                new SortAggregationStep("score", true)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, result.size());
        assertEquals(20, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(30, result.get(1).get("score").asJsonNumber().asInteger());
    }

    // SORT on a mixed scalar+object indexed field includes all docs (scalar and object-valued)
    @Test
    public void test_sort_mixed_type_indexed_field_includes_object_valued_docs() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "s1", "meta", new JsonString("alpha"));
        addDoc(cache, "s2", "meta", new JsonString("beta"));
        final var obj = new JsonObject();
        obj.addProperty("k", 1);
        addDoc(cache, "o1", "meta", obj);
        enableIndex(cache, "meta");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new SortAggregationStep("meta", true)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(3, result.size());
        final var ids = result.stream().map(r -> r.get(Globals.PK_FIELD).asJsonString().getValue())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("s1", "s2", "o1"), ids);
    }

    // Index-backed SORT followed by LIMIT returns only the first N docs in sorted order;
    // the lazy document-fetch path must not return more than LIMIT elements.
    @Test
    public void test_sort_via_index_with_limit_returns_correct_first_n() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "v1", "score", new JsonNumber(50));
        addDoc(cache, "v2", "score", new JsonNumber(10));
        addDoc(cache, "v3", "score", new JsonNumber(30));
        addDoc(cache, "v4", "score", new JsonNumber(20));
        addDoc(cache, "v5", "score", new JsonNumber(40));
        enableIndex(cache, "score");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new SortAggregationStep("score", true), new LimitAggregationStep(3)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(3, result.size());
        assertEquals(10, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(20, result.get(1).get("score").asJsonNumber().asInteger());
        assertEquals(30, result.get(2).get("score").asJsonNumber().asInteger());
    }

    // Index-backed SORT descending with LIMIT returns the top-N docs in correct order
    @Test
    public void test_sort_via_index_descending_with_limit_returns_top_n() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "w1", "score", new JsonNumber(10));
        addDoc(cache, "w2", "score", new JsonNumber(50));
        addDoc(cache, "w3", "score", new JsonNumber(30));
        addDoc(cache, "w4", "score", new JsonNumber(20));
        addDoc(cache, "w5", "score", new JsonNumber(40));
        enableIndex(cache, "score");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new SortAggregationStep("score", false), new LimitAggregationStep(2)));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, result.size());
        assertEquals(50, result.get(0).get("score").asJsonNumber().asInteger());
        assertEquals(40, result.get(1).get("score").asJsonNumber().asInteger());
    }
}
