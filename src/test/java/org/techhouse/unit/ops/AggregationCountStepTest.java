package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class AggregationCountStepTest {
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

    @Test
    public void test_count_on_empty_collection_returns_zero() throws IOException {
        var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        var steps = new ArrayList<BaseAggregationStep>();
        steps.add(new CountAggregationStep());
        request.setAggregationSteps(steps);

        var result = AggregationOperationHelper.processAggregation(request);

        assertNotNull(result);
        assertEquals(1, result.size());
        var countResult = result.getFirst();
        assertTrue(countResult.has("count"));
        assertEquals(0, countResult.get("count").asJsonNumber().asInteger());
    }

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

    // The whole-collection COUNT is derived from the (synchronously-maintained) PK index size, so
    // register the ids there. Populated directly to be independent of cache admission decisions.
    private void registerPkIndex(String... ids) throws NoSuchFieldException, IllegalAccessException {
        final var userCache = IocContainer.get(UserCache.class);
        final var token = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(userCache, "pkIndexMap", token);
        final var list = new ArrayList<PkIndexEntry>();
        for (var id : ids) {
            list.add(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, id, 0, 100, 0));
        }
        pkIndexMap.put(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL), list);
    }

    @Test
    public void test_count_returns_document_count() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "c1", "val", "a");
        insertEntry(cache, "c2", "val", "b");
        insertEntry(cache, "c3", "val", "c");
        registerPkIndex("c1", "c2", "c3");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new CountAggregationStep()));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().has("count"));
        assertEquals(3, result.getFirst().get("count").asJsonNumber().asInteger());
    }

    private void addDoc(Cache cache, String id, JsonBaseElement value) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("status", value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void enableIndex(Cache cache) {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status"));
    }

    private static int countOf(List<JsonObject> result) {
        assertEquals(1, result.size());
        assertTrue(result.getFirst().has("count"));
        return result.getFirst().get("count").asJsonNumber().asInteger();
    }

    private void addDocWithFields(Cache cache, String id, JsonBaseElement v1, String f2, JsonBaseElement v2) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("status", v1);
        obj.add(f2, v2);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    @Test
    public void test_count_after_indexed_filter_reads_no_documents() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        addDoc(cache, "c2", new JsonString("inactive"));
        addDoc(cache, "c3", new JsonString("active"));
        enableIndex(cache);
        IocContainer.get(org.techhouse.cache.UserCache.class).evictCollectionDocuments(TestGlobals.DB,
                TestGlobals.COLL);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep()));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, countOf(result));
    }

    @Test
    public void test_count_after_unindexed_filter_falls_back_and_is_correct() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        addDoc(cache, "c2", new JsonString("inactive"));
        addDoc(cache, "c3", new JsonString("active"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep()));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(2, countOf(result));
    }

    @Test
    public void test_count_after_filter_indexed_matches_unindexed() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        addDoc(cache, "c2", new JsonString("inactive"));
        addDoc(cache, "c3", new JsonString("active"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep()));
        final var scanCount = countOf(AggregationOperationHelper.processAggregation(req));

        enableIndex(cache);
        final var indexedCount = countOf(AggregationOperationHelper.processAggregation(req));

        assertEquals(scanCount, indexedCount);
        assertEquals(2, indexedCount);
    }

    // Engine robustness: the request validator rejects a COUNT that is not the last step, but if
    // such a pipeline reaches the engine directly the fast-path {count:N} stream is still fed to the
    // trailing steps.
    @Test
    public void test_count_after_filter_with_trailing_step_still_runs() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        addDoc(cache, "c2", new JsonString("active"));
        enableIndex(cache);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep(), new SkipAggregationStep(1)));
        final var result = AggregationOperationHelper.processAggregation(req);

        // The single count object is skipped, proving the trailing step executed on the fast-path stream.
        assertTrue(result.isEmpty());
    }

    @Test
    public void test_count_after_indexed_and_conjunction() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "level", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "level", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "level", new JsonNumber(1));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "level");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status", "level"));

        final var and = new org.techhouse.ops.req.agg.operators.ConjunctionOperator(
                org.techhouse.ops.req.agg.ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new FilterAggregationStep(and), new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_indexed_or_conjunction() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "level", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "level", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "level", new JsonNumber(3));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "level");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status", "level"));

        final var or = new org.techhouse.ops.req.agg.operators.ConjunctionOperator(
                org.techhouse.ops.req.agg.ConjunctionOperatorType.OR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(3))));
        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new FilterAggregationStep(or), new CountAggregationStep()));

        assertEquals(3, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_partially_indexed_conjunction_falls_back() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "level", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "level", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "level", new JsonNumber(1));
        enableIndex(cache);

        final var and = new org.techhouse.ops.req.agg.operators.ConjunctionOperator(
                org.techhouse.ops.req.agg.ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new FilterAggregationStep(and), new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_only_step_is_unaffected() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
        registerPkIndex("c1");
        enableIndex(cache);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_indexed_filter_no_matches_returns_zero() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        enableIndex(cache);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("missing"))),
                new CountAggregationStep()));

        assertEquals(0, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_multiple_indexed_filters_intersects() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "level", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "level", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "level", new JsonNumber(1));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "level");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status", "level"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))),
                new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_indexed_filter_then_map_skips_map() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        addDoc(cache, "c2", new JsonString("inactive"));
        addDoc(cache, "c3", new JsonString("active"));
        enableIndex(cache);
        IocContainer.get(org.techhouse.cache.UserCache.class).evictCollectionDocuments(TestGlobals.DB,
                TestGlobals.COLL);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new MapAggregationStep(
                        List.of(new org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator("extra", null))),
                new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_indexed_filter_then_join_skips_join() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDocWithFields(cache, "c1", new JsonString("active"), "ref", new JsonNumber(1));
        addDocWithFields(cache, "c2", new JsonString("active"), "ref", new JsonNumber(2));
        addDocWithFields(cache, "c3", new JsonString("inactive"), "ref", new JsonNumber(1));
        enableIndex(cache);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined"), new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_indexed_filter_then_sort_skips_sort() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        addDoc(cache, "c2", new JsonString("active"));
        addDoc(cache, "c3", new JsonString("inactive"));
        enableIndex(cache);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new SortAggregationStep("status", true), new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_map_only_uses_whole_collection_count()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "c1", "status", "active");
        insertEntry(cache, "c2", "status", "inactive");
        registerPkIndex("c1", "c2");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new MapAggregationStep(
                        List.of(new org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator("extra", null))),
                new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_group_by_falls_back_to_group_count() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        addDoc(cache, "c2", new JsonString("active"));
        enableIndex(cache);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new GroupByAggregationStep("status"), new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_with_filter_after_map_falls_back() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        addDoc(cache, "c2", new JsonString("inactive"));
        addDoc(cache, "c3", new JsonString("active"));
        enableIndex(cache);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new MapAggregationStep(
                        List.of(new org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator("extra", null))),
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new CountAggregationStep()));

        assertEquals(2, countOf(AggregationOperationHelper.processAggregation(req)));
    }

    @Test
    public void test_count_after_limit_falls_back() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "c1", new JsonString("active"));
        addDoc(cache, "c2", new JsonString("active"));
        enableIndex(cache);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"))),
                new LimitAggregationStep(1), new CountAggregationStep()));

        assertEquals(1, countOf(AggregationOperationHelper.processAggregation(req)));
    }
}
