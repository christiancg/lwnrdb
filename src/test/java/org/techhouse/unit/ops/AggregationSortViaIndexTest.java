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

    private void insert(String id, double value) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.addProperty(FIELD, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void enableIndex() {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
    }

    private List<Double> run(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request).stream()
                .map(o -> o.get(FIELD).asJsonNumber().getValue().doubleValue()).toList();
    }

    private void seed(int count) {
        for (var i = 0; i < count; i++) {
            insert(String.format("d%04d", i), (i * 37) % count);
        }
        enableIndex();
    }

    @Test
    public void test_index_sort_spanning_several_fetch_chunks_stays_ordered() throws IOException {
        seed(700);

        final var sorted = run(new SortAggregationStep(FIELD, true));

        assertEquals(700, sorted.size(), "every indexed document must survive the chunked fetch");
        for (var i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i - 1) <= sorted.get(i), "chunked fetching must not reorder the index sort");
        }
    }

    @Test
    public void test_index_sort_descending_spanning_chunks_stays_ordered() throws IOException {
        seed(600);

        final var sorted = run(new SortAggregationStep(FIELD, false));

        assertEquals(600, sorted.size());
        for (var i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i - 1) >= sorted.get(i));
        }
    }

    @Test
    public void test_index_sort_with_limit_matches_the_head_of_the_full_sort() throws IOException {
        seed(700);

        final var full = run(new SortAggregationStep(FIELD, true));
        final var limited = run(new SortAggregationStep(FIELD, true), new LimitAggregationStep(10));

        assertEquals(full.subList(0, 10), limited);
    }

    @Test
    public void test_index_sort_with_skip_and_limit_matches_the_same_window() throws IOException {
        seed(700);

        final var full = run(new SortAggregationStep(FIELD, true));
        final var window = run(new SortAggregationStep(FIELD, true), new SkipAggregationStep(300),
                new LimitAggregationStep(20));

        assertEquals(full.subList(300, 320), window);
    }

    @Test
    public void test_index_sort_with_a_limit_smaller_than_one_chunk_stops_early() throws IOException {
        seed(700);

        final var limited = run(new SortAggregationStep(FIELD, true), new LimitAggregationStep(3));

        assertEquals(3, limited.size());
        assertEquals(run(new SortAggregationStep(FIELD, true)).subList(0, 3), limited);
    }

    @Test
    public void test_index_sort_drops_ids_whose_document_is_gone() throws IOException {
        seed(300);
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "d0005");

        final var sorted = run(new SortAggregationStep(FIELD, true));

        assertEquals(299, sorted.size(), "an id with no document must be dropped, not fetched as null");
    }

    @Test
    public void test_a_pending_write_is_sorted_by_its_current_value_not_the_stale_index() throws IOException {
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
    public void test_pending_write_fallback_matches_the_bounded_read() throws IOException {
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
    public void test_a_hash_indexed_field_falls_back_and_still_sorts() throws IOException {
        for (var i = 0; i < 5; i++) {
            final var obj = new JsonObject();
            obj.add(Globals.PK_FIELD, new JsonString("h" + i));
            final var tags = new JsonArray();
            tags.add(new JsonString("t" + i));
            obj.add("tags", tags);
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
            entry.set_id("h" + i);
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        }
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "tags");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("tags"));

        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep("tags", true)));
        final var results = AggregationOperationHelper.processAggregation(request);

        assertEquals(5, results.size(), "a hash-indexed field must fall back to the full index read");
    }

    @Test
    public void test_index_sort_below_one_chunk_is_unaffected() throws IOException {
        insert("a", 30);
        insert("b", 10);
        insert("c", 20);
        enableIndex();

        assertEquals(List.of(10d, 20d, 30d), run(new SortAggregationStep(FIELD, true)));
    }
}
