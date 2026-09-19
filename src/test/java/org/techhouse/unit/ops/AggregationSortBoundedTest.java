package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationSortBoundedTest {
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

    private void insert(String id, JsonBaseElement value) throws IOException {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        if (value != null) {
            obj.add(FIELD, value);
        }
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
    }

    private void insertNumber(String id, double value) throws IOException {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.addProperty(FIELD, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
    }

    private List<String> run(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request).stream()
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    private static SortAggregationStep sort(boolean ascending) {
        return new SortAggregationStep(FIELD, ascending);
    }

    @Test
    public void test_duplicate_sort_keys_keep_encounter_order() throws IOException {
        for (var i = 0; i < 12; i++) {
            insertNumber(String.format("d%02d", i), 5);
        }
        final var sourceOrder = run();

        assertEquals(sourceOrder, run(sort(true)), "documents sharing one sort key must keep their encounter order");
        assertEquals(sourceOrder, run(sort(false)), "a descending sort must break ties on encounter order too");
        assertEquals(sourceOrder.subList(0, 4), run(sort(true), new LimitAggregationStep(4)),
                "the bounded path must break ties on encounter order too");
    }

    @Test
    public void test_bounded_sort_matches_the_full_sort_on_ties() throws IOException {
        for (var i = 0; i < 20; i++) {
            insertNumber(String.format("t%02d", i), i % 3);
        }

        final var full = run(sort(true));
        final var bounded = run(sort(true), new LimitAggregationStep(5));

        assertEquals(full.subList(0, 5), bounded, "a SORT fused with LIMIT must match the head of the full sort");
    }

    @Test
    public void test_bounded_sort_with_a_skip_matches_the_full_sort() throws IOException {
        for (var i = 0; i < 20; i++) {
            insertNumber(String.format("t%02d", i), i % 4);
        }

        final var full = run(sort(true));
        final var bounded = run(sort(true), new SkipAggregationStep(6), new LimitAggregationStep(5));

        assertEquals(full.subList(6, 11), bounded, "SORT + SKIP + LIMIT must match the same window of the full sort");
    }

    @Test
    public void test_bounded_descending_sort_matches_the_full_sort() throws IOException {
        for (var i = 0; i < 15; i++) {
            insertNumber(String.format("t%02d", i), i % 5);
        }

        final var full = run(sort(false));
        final var bounded = run(sort(false), new LimitAggregationStep(4));

        assertEquals(full.subList(0, 4), bounded);
    }

    @Test
    public void test_mixed_type_sort_field_keeps_a_total_order() throws IOException {
        insert("m1", new JsonString("beta"));
        insertNumber("m2", 7);
        insert("m3", new JsonBoolean(true));
        insert("m4", new JsonString("alpha"));
        insertNumber("m5", 2);
        insert("m6", null);

        final var full = assertDoesNotThrow(() -> run(sort(true)));
        assertEquals(6, full.size());
        assertEquals("m6", full.getLast(), "a document without the sort field sorts last");
        assertEquals(full.subList(0, 3), run(sort(true), new LimitAggregationStep(3)),
                "the bounded path must agree with the full sort on a mixed-type field");
    }

    @Test
    public void test_limit_larger_than_the_stream_returns_everything_sorted() throws IOException {
        for (var i = 10; i > 0; i--) {
            insertNumber("n" + i, i);
        }

        final var bounded = run(sort(true), new LimitAggregationStep(100));

        assertEquals(run(sort(true)), bounded);
    }

    @Test
    public void test_a_large_stream_sorts_identically_on_the_parallel_path() throws IOException {
        final var count = 9000;
        for (var i = 0; i < count; i++) {
            insertNumber(String.format("p%05d", i), (i * 7919) % 1000);
        }

        final var sorted = run(sort(true));

        assertEquals(count, sorted.size());
        var previous = Double.NEGATIVE_INFINITY;
        for (final var id : sorted) {
            final var value = cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, java.util.Set.of(id)).getFirst()
                    .getData().get(FIELD).asJsonNumber().getValue().doubleValue();
            assertTrue(value >= previous, "the parallel sort must produce a non-decreasing sequence");
            previous = value;
        }
    }

    @Test
    public void test_sort_without_a_following_limit_is_unbounded() throws IOException {
        for (var i = 0; i < 8; i++) {
            insertNumber("u" + i, 8 - i);
        }

        final var sorted = run(sort(true), new SkipAggregationStep(2));

        assertEquals(6, sorted.size(), "a SKIP with no LIMIT must not bound the sort");
        assertEquals(List.of("u5", "u4", "u3", "u2", "u1", "u0"), sorted);
    }
}
