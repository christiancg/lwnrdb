package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationSortViaIndexTest {
    private static final String FIELD = "score";
    private Cache cache;

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private void insert(String id, double value) throws IOException {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.addProperty(FIELD, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void enableIndex() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
    }

    private List<Double> run(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request).stream()
                .map(o -> o.get(FIELD).asJsonNumber().getValue().doubleValue()).toList();
    }

    private void seed(int count) throws InterruptedException, IOException {
        for (var i = 0; i < count; i++) {
            insert(String.format("d%04d", i), (i * 37) % count);
        }
        enableIndex();
    }

    private void save(String id, Double value) {
        final var request = new org.techhouse.ops.req.SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        if (value != null) {
            obj.addProperty(FIELD, value);
        }
        request.setObject(obj);
        request.set_id(id);
        IocContainer.get(org.techhouse.ops.OperationProcessor.class).processMessage(request);
    }

    private void saveNullField(String id) {
        final var request = new org.techhouse.ops.req.SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(FIELD, org.techhouse.ejson.elements.JsonNull.INSTANCE);
        request.setObject(obj);
        request.set_id(id);
        IocContainer.get(org.techhouse.ops.OperationProcessor.class).processMessage(request);
    }

    private List<String> runIds(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request).stream()
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    @Test
    public void test_sort_includes_documents_missing_the_field() throws IOException, InterruptedException {
        save("s1", 3d);
        save("s2", 1d);
        save("s3", null);
        enableIndex();

        final var ids = runIds(new SortAggregationStep(FIELD, true));

        assertEquals(3, ids.size(), "a document without the sort field must not be filtered out: " + ids);
        assertTrue(ids.containsAll(List.of("s1", "s2", "s3")), ids.toString());
    }

    @Test
    public void test_sort_includes_explicit_nulls() throws IOException, InterruptedException {
        save("n1", 3d);
        save("n2", 1d);
        saveNullField("n3");
        enableIndex();

        final var ids = runIds(new SortAggregationStep(FIELD, true));

        assertEquals(3, ids.size(), "an explicit null must not be filtered out: " + ids);
        assertTrue(ids.containsAll(List.of("n1", "n2", "n3")), ids.toString());
    }

    @Test
    public void test_indexed_sort_matches_unindexed_sort() throws IOException, InterruptedException {
        save("m1", 3d);
        save("m2", 1d);
        save("m3", null);
        saveNullField("m4");
        final var unindexed = runIds(new SortAggregationStep(FIELD, true));

        enableIndex();
        final var indexed = runIds(new SortAggregationStep(FIELD, true));

        assertEquals(unindexed, indexed, "the index fast path must answer exactly what the full scan answers");
    }

    @Test
    public void test_index_sort_spanning_several_fetch_chunks_stays_ordered() throws IOException, InterruptedException {
        seed(700);

        final var sorted = run(new SortAggregationStep(FIELD, true));

        assertEquals(700, sorted.size(), "every indexed document must survive the chunked fetch");
        for (var i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i - 1) <= sorted.get(i), "chunked fetching must not reorder the index sort");
        }
    }

    @Test
    public void test_index_sort_descending_spanning_chunks_stays_ordered() throws IOException, InterruptedException {
        seed(600);

        final var sorted = run(new SortAggregationStep(FIELD, false));

        assertEquals(600, sorted.size());
        for (var i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i - 1) >= sorted.get(i));
        }
    }

    @Test
    public void test_index_sort_with_limit_matches_the_head_of_the_full_sort()
            throws IOException, InterruptedException {
        seed(700);

        final var full = run(new SortAggregationStep(FIELD, true));
        final var limited = run(new SortAggregationStep(FIELD, true), new LimitAggregationStep(10));

        assertEquals(full.subList(0, 10), limited);
    }

    @Test
    public void test_index_sort_with_skip_and_limit_matches_the_same_window() throws IOException, InterruptedException {
        seed(700);

        final var full = run(new SortAggregationStep(FIELD, true));
        final var window = run(new SortAggregationStep(FIELD, true), new SkipAggregationStep(300),
                new LimitAggregationStep(20));

        assertEquals(full.subList(300, 320), window);
    }

    @Test
    public void test_index_sort_with_a_limit_smaller_than_one_chunk_stops_early()
            throws IOException, InterruptedException {
        seed(700);

        final var limited = run(new SortAggregationStep(FIELD, true), new LimitAggregationStep(3));

        assertEquals(3, limited.size());
        assertEquals(run(new SortAggregationStep(FIELD, true)).subList(0, 3), limited);
    }

    @Test
    public void test_index_sort_drops_ids_whose_document_is_gone() throws IOException, InterruptedException {
        seed(300);
        TestUtils.uncacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, "d0005");

        final var sorted = run(new SortAggregationStep(FIELD, true));

        assertEquals(299, sorted.size(), "an id with no document must be dropped, not fetched as null");
    }

    @Test
    public void test_a_pending_write_is_sorted_by_its_current_value_not_the_stale_index()
            throws IOException, InterruptedException {
        seed(300);
        final var pending = IocContainer.get(PendingIndexWrites.class);
        insert("d0007", 999999);
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "d0007");
        try {
            final var sorted = run(new SortAggregationStep(FIELD, true));

            assertEquals(300, sorted.size(), "a pending write must not drop documents from the sort");
            assertEquals(999999d, sorted.getLast(),
                    "a committed-but-unindexed document must sort by its current value");
            for (var i = 1; i < sorted.size(); i++) {
                assertTrue(sorted.get(i - 1) <= sorted.get(i));
            }
        } finally {
            pending.clear(TestGlobals.DB, TestGlobals.COLL, "d0007");
        }
    }

    @Test
    public void test_pending_write_fallback_matches_the_bounded_read() throws IOException, InterruptedException {
        seed(400);
        final var expected = run(new SortAggregationStep(FIELD, true), new LimitAggregationStep(25));
        final var pending = IocContainer.get(PendingIndexWrites.class);
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "d0011");
        try {
            assertEquals(expected, run(new SortAggregationStep(FIELD, true), new LimitAggregationStep(25)),
                    "the fallback path must produce the same order as the bounded index read");
        } finally {
            pending.clear(TestGlobals.DB, TestGlobals.COLL, "d0011");
        }
    }

    @Test
    public void test_a_hash_indexed_field_falls_back_and_still_sorts() throws IOException, InterruptedException {
        for (var i = 0; i < 5; i++) {
            final var obj = new JsonObject();
            obj.add(Globals.PK_FIELD, new JsonString("h" + i));
            final var tags = new JsonArray();
            tags.add(new JsonString("t" + i));
            obj.add("tags", tags);
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
            entry.set_id("h" + i);
            TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        }
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "tags");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("tags"));

        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep("tags", true)));
        final var results = AggregationOperationHelper.processAggregation(request);

        assertEquals(5, results.size(), "a hash-indexed field must fall back to the full index read");
    }

    @Test
    public void test_index_sort_below_one_chunk_is_unaffected() throws IOException, InterruptedException {
        insert("a", 30);
        insert("b", 10);
        insert("c", 20);
        enableIndex();

        assertEquals(List.of(10d, 20d, 30d), run(new SortAggregationStep(FIELD, true)));
    }

    private void insertTyped(String id, org.techhouse.ejson.elements.JsonBaseElement value) throws IOException {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(FIELD, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private List<String> idsFrom(boolean ascending, Integer limit) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        final var steps = new java.util.ArrayList<BaseAggregationStep>();
        steps.add(new SortAggregationStep(FIELD, ascending));
        if (limit != null) {
            steps.add(new LimitAggregationStep(limit));
        }
        request.setAggregationSteps(steps);
        return AggregationOperationHelper.processAggregation(request).stream()
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    @Test
    public void test_boolean_field_sorts_identically_via_index_and_scan() throws Exception {
        insertTyped("f", new org.techhouse.ejson.elements.JsonBoolean(false));
        insertTyped("t", new org.techhouse.ejson.elements.JsonBoolean(true));
        final var scanAscending = idsFrom(true, null);
        final var scanDescending = idsFrom(false, null);
        enableIndex();

        assertEquals(scanAscending, idsFrom(true, null),
                "false sorts before true on the scan, so the index path must agree");
        assertEquals(scanDescending, idsFrom(false, null));
    }

    @Test
    public void test_mixed_type_field_sorts_identically_via_index_and_scan() throws Exception {
        insertTyped("n1", new org.techhouse.ejson.elements.JsonNumber(5));
        insertTyped("n2", new org.techhouse.ejson.elements.JsonNumber(7));
        insertTyped("s1", new JsonString("abc"));
        insertTyped("b1", new org.techhouse.ejson.elements.JsonBoolean(true));
        final var scanAscending = idsFrom(true, null);
        final var scanDescending = idsFrom(false, null);
        enableIndex();

        assertEquals(scanAscending, idsFrom(true, null),
                "the index comparator must rank types the same way the in-memory sort does");
        assertEquals(scanDescending, idsFrom(false, null));
    }

    @Test
    public void test_sort_via_index_does_not_violate_the_comparator_contract() throws Exception {
        for (var i = 0; i < 40; i++) {
            if (i % 3 == 0) {
                insertTyped("d" + i, new org.techhouse.ejson.elements.JsonNumber(i));
            } else if (i % 3 == 1) {
                insertTyped("d" + i, new JsonString("s" + i));
            } else {
                insertTyped("d" + i, new org.techhouse.ejson.elements.JsonBoolean(i % 2 == 0));
            }
        }
        final var scanOrder = ascendingSortKeys();
        enableIndex();

        assertEquals(scanOrder, ascendingSortKeys(),
                "a comparator that is not a total order makes TimSort reject the sort outright");
    }

    private List<String> ascendingSortKeys() throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep(FIELD, true)));
        return AggregationOperationHelper.processAggregation(request).stream().map(o -> {
            final var primitive = o.get(FIELD).asJsonPrimitive();
            return primitive.getClass().getSimpleName() + ":" + primitive.getValue();
        }).toList();
    }

    @Test
    public void test_tied_sort_keys_are_ordered_deterministically() throws Exception {
        insert("a", 5);
        insert("b", 5);
        insert("c", 9);
        enableIndex();

        final var first = idsFrom(true, 1);
        for (var run = 0; run < 10; run++) {
            assertEquals(first, idsFrom(true, 1), "SORT + LIMIT must not pick a different tied document per run");
        }
    }

    @Test
    public void test_indexed_and_scanned_sort_agree_on_tied_keys() throws Exception {
        for (var i = 0; i < 10; i++) {
            insert(String.format("p%02d", i), 5);
        }
        final var scanned = idsSorted();
        enableIndex();
        final var indexed = idsSorted();

        assertEquals(scanned, indexed, "a page must not change because a field happened to gain an index");
        assertEquals(scanned.subList(3, 6), indexed.subList(3, 6), "SKIP + LIMIT must select the same page");
    }

    private List<String> idsSorted() throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep(FIELD, true)));
        return AggregationOperationHelper.processAggregation(request).stream()
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }
}
