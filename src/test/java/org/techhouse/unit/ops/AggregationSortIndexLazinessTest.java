package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.index.RangeIdWalk;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.RangeBounds;

public class AggregationSortIndexLazinessTest {
    private static final String COLL = "lazysort";
    private static final String FIELD = "score";
    private static final int DOCUMENTS = 700;
    private static final int CHUNK = 256;
    private static final int FIRST_CHUNK = 32;

    private UserCache userCache;
    private OperationProcessor processor;

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        userCache = IocContainer.get(Cache.class).userCache();
        processor = new OperationProcessor();
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLL));
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var obj = new JsonObject();
            obj.add(Globals.PK_FIELD, new JsonString(String.format("d%04d", i)));
            obj.addProperty(FIELD, (i * 37) % DOCUMENTS);
            documents.add(obj);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.DB, COLL);
        bulk.setObjects(documents);
        processor.processMessage(bulk);
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, COLL, FIELD));
        drainBackgroundWrites();
    }

    private void drainBackgroundWrites() throws IOException, InterruptedException {
        final var pending = IocContainer.get(PendingIndexWrites.class);
        for (final var id : pending.idsFor(TestGlobals.DB, COLL)) {
            final var entries = IocContainer.get(Cache.class).getEntriesByIds(TestGlobals.DB, COLL, Set.of(id));
            if (!entries.isEmpty()) {
                EventProcessorHelper
                        .processEvent(new EntityEvent(EventType.CREATED, TestGlobals.DB, COLL, entries.getFirst()));
            }
        }
        assertTrue(pending.idsFor(TestGlobals.DB, COLL).isEmpty(), "the pending overlay must be drained");
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException, IOException, InterruptedException {
        processor.processMessage(new DropCollectionRequest(TestGlobals.DB, COLL));
        TestUtils.standardTearDown();
    }

    private List<JsonObject> run(BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, COLL);
        request.setAggregationSteps(List.of(steps));
        final var response = processor.processMessage(request);
        return response instanceof AggregateResponse aggregate ? aggregate.getResults() : List.of();
    }

    private int cachedDocuments() {
        final var cached = userCache.getCachedCollection(TestGlobals.DB, COLL);
        return cached == null ? 0 : cached.size();
    }

    @Test
    public void test_a_small_limit_fetches_only_the_first_chunk() {
        userCache.evictCollectionDocuments(TestGlobals.DB, COLL);
        assertEquals(0, cachedDocuments());

        final var results = run(new SortAggregationStep(FIELD, true), new LimitAggregationStep(3));

        assertEquals(3, results.size());
        final var fetched = cachedDocuments();
        assertTrue(fetched > 0, "the sort must have fetched something");
        assertTrue(fetched <= 3 + FIRST_CHUNK,
                "a LIMIT after an index sort must stop after the first fetch chunk, but " + fetched + " were read");
    }

    @Test
    public void test_no_limit_fetches_every_document() {
        userCache.evictCollectionDocuments(TestGlobals.DB, COLL);

        final var results = run(new SortAggregationStep(FIELD, true));

        assertEquals(DOCUMENTS, results.size());
        assertEquals(DOCUMENTS, cachedDocuments(), "an unbounded index sort must still read the whole collection");
    }

    @Test
    public void test_a_limit_spanning_two_chunks_stops_after_the_second() {
        userCache.evictCollectionDocuments(TestGlobals.DB, COLL);

        final var results = run(new SortAggregationStep(FIELD, true), new LimitAggregationStep(CHUNK + 1));

        assertEquals(CHUNK + 1, results.size());
        assertTrue(cachedDocuments() <= CHUNK * 2 + FIRST_CHUNK,
                "a limit just past one chunk must not read the whole collection");
    }

    @Test
    public void test_the_limited_result_matches_the_head_of_the_full_sort() {
        final var full = run(new SortAggregationStep(FIELD, true)).stream()
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
        userCache.evictCollectionDocuments(TestGlobals.DB, COLL);

        final var limited = run(new SortAggregationStep(FIELD, true), new LimitAggregationStep(10)).stream()
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();

        assertEquals(full.subList(0, 10), limited);
    }

    private static FilterAggregationStep bound(FieldOperatorType type, double value) {
        return new FilterAggregationStep(new FieldOperator(type, FIELD, new JsonNumber(value)));
    }

    private static FilterAggregationStep between(double low, double high) {
        return new FilterAggregationStep(new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.GREATER_THAN_EQUALS, FIELD, new JsonNumber(low)),
                        new FieldOperator(FieldOperatorType.SMALLER_THAN, FIELD, new JsonNumber(high)))));
    }

    private List<String> ids(BaseAggregationStep... steps) {
        return run(steps).stream().map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    private List<String> scanned(BaseAggregationStep... steps) {
        final var prefixed = new ArrayList<BaseAggregationStep>();
        prefixed.add(new SkipAggregationStep(0));
        prefixed.addAll(List.of(steps));
        return ids(prefixed.toArray(BaseAggregationStep[]::new));
    }

    private void save(String id, JsonBaseElement value) {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString(id));
        document.add(FIELD, value);
        final var request = new SaveRequest(TestGlobals.DB, COLL);
        request.setObject(document);
        processor.processMessage(request);
    }

    private void forgetPendingWrites() {
        final var pending = IocContainer.get(PendingIndexWrites.class);
        pending.clear(TestGlobals.DB, COLL, pending.idsFor(TestGlobals.DB, COLL), 0L);
        assertTrue(pending.idsFor(TestGlobals.DB, COLL).isEmpty());
    }

    @Test
    public void test_a_range_top_k_fetches_only_the_bound() {
        userCache.evictCollectionDocuments(TestGlobals.DB, COLL);

        final var top = ids(between(100, 600), new SortAggregationStep(FIELD, true), new LimitAggregationStep(5));

        assertEquals(5, top.size());
        assertTrue(cachedDocuments() <= 5,
                "a range top-K must fetch the bound, but " + cachedDocuments() + " were read");
        assertEquals(scanned(between(100, 600), new SortAggregationStep(FIELD, true), new LimitAggregationStep(5)),
                top);
    }

    @Test
    public void test_range_top_k_matches_the_scan_for_every_shape_direction_and_limit() {
        final var shapes = List.of(bound(FieldOperatorType.GREATER_THAN, 650),
                bound(FieldOperatorType.GREATER_THAN_EQUALS, 650), bound(FieldOperatorType.SMALLER_THAN, 40),
                bound(FieldOperatorType.SMALLER_THAN_EQUALS, 40), between(100, 600), between(350, 352),
                new FilterAggregationStep(new ConjunctionOperator(ConjunctionOperatorType.AND,
                        List.of(new FieldOperator(FieldOperatorType.GREATER_THAN, FIELD, new JsonNumber(10)),
                                new FieldOperator(FieldOperatorType.SMALLER_THAN_EQUALS, FIELD, new JsonNumber(90)),
                                new FieldOperator(FieldOperatorType.GREATER_THAN_EQUALS, FIELD, new JsonNumber(5))))),
                bound(FieldOperatorType.GREATER_THAN, 10_000));
        for (final var shape : shapes) {
            for (final var ascending : List.of(true, false)) {
                for (final var limit : List.of(1, 5, 50, 1000)) {
                    final var sort = new SortAggregationStep(FIELD, ascending);
                    assertEquals(scanned(shape, sort, new LimitAggregationStep(limit)),
                            ids(shape, sort, new LimitAggregationStep(limit)),
                            shape.getOperator() + " ascending=" + ascending + " limit=" + limit);
                }
            }
        }
    }

    @Test
    public void test_a_skip_after_the_sort_selects_the_same_window() {
        final var sort = new SortAggregationStep(FIELD, false);

        assertEquals(scanned(between(100, 600), sort, new SkipAggregationStep(3), new LimitAggregationStep(4)),
                ids(between(100, 600), sort, new SkipAggregationStep(3), new LimitAggregationStep(4)));
    }

    @Test
    public void test_a_stale_entry_outside_the_range_is_skipped_and_the_bound_is_still_filled() {
        final var sort = new SortAggregationStep(FIELD, true);
        final var head = ids(between(100, 600), sort, new LimitAggregationStep(5));
        save(head.getFirst(), new JsonNumber(5000));
        save(head.get(2), new JsonNumber(5001));
        forgetPendingWrites();

        final var top = ids(between(100, 600), sort, new LimitAggregationStep(5));

        assertEquals(5, top.size());
        assertFalse(top.contains(head.getFirst()));
        assertEquals(scanned(between(100, 600), sort, new LimitAggregationStep(5)), top);
    }

    @Test
    public void test_a_descending_walk_resumes_past_stale_entries() {
        final var sort = new SortAggregationStep(FIELD, false);
        final var head = ids(between(100, 600), sort, new LimitAggregationStep(4));
        save(head.get(1), new JsonNumber(-1));
        save(head.get(3), new JsonNumber(-2));
        forgetPendingWrites();

        final var top = ids(between(100, 600), sort, new LimitAggregationStep(4));

        assertEquals(4, top.size());
        assertEquals(scanned(between(100, 600), sort, new LimitAggregationStep(4)), top);
    }

    @Test
    public void test_a_field_without_numeric_values_declines_and_matches_the_scan() {
        for (var i = 0; i < 5; i++) {
            save("label" + i, new JsonString("v" + i));
        }
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, COLL, "label"));
        final var steps = new BaseAggregationStep[]{
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.GREATER_THAN, "label", new JsonNumber(1))),
                new SortAggregationStep("label", true), new LimitAggregationStep(3)};

        assertEquals(scanned(steps), ids(steps));
        assertTrue(ids(steps).isEmpty());
    }

    @Test
    public void test_a_pending_write_declines_and_still_matches_the_scan() {
        final var sort = new SortAggregationStep(FIELD, true);
        final var head = ids(between(100, 600), sort, new LimitAggregationStep(3));
        save(head.getFirst(), new JsonNumber(450));

        assertFalse(IocContainer.get(PendingIndexWrites.class).idsFor(TestGlobals.DB, COLL).isEmpty());
        assertEquals(scanned(between(100, 600), sort, new LimitAggregationStep(3)),
                ids(between(100, 600), sort, new LimitAggregationStep(3)));
    }

    @Test
    public void test_a_walk_without_the_collection_lock_declines() throws IOException {
        final var bounds = RangeBounds.fromLeaves(FIELD,
                List.of(new FieldOperator(FieldOperatorType.GREATER_THAN_EQUALS, FIELD, new JsonNumber(100)),
                        new FieldOperator(FieldOperatorType.SMALLER_THAN, FIELD, new JsonNumber(600))));

        assertNull(RangeIdWalk.open(TestGlobals.DB, COLL, bounds, true, 5, ReadObserver.NONE),
                "a dirty read or a LISTEN re-run holds no collection lock, so a walk paging past writers must decline");
    }

    @Test
    public void test_mixed_kinds_and_tied_values_answer_like_the_scan() throws IOException, InterruptedException {
        final var array = new JsonArray();
        array.add(new JsonNumber(150));
        save("mixed-string", new JsonString("150"));
        save("mixed-bool", JsonBoolean.of(true));
        save("mixed-array", array);
        for (var i = 9; i >= 0; i--) {
            save("tie" + i, new JsonNumber(200));
        }
        drainBackgroundWrites();
        for (final var ascending : List.of(true, false)) {
            for (final var limit : List.of(3, 101, 105)) {
                final var sort = new SortAggregationStep(FIELD, ascending);
                assertEquals(scanned(between(100, 301), sort, new LimitAggregationStep(limit)),
                        ids(between(100, 301), sort, new LimitAggregationStep(limit)),
                        "ascending=" + ascending + " limit=" + limit);
            }
        }
    }

    @Test
    public void test_analyze_reports_the_index_and_the_documents_fetched() {
        final var request = new AggregateRequest(TestGlobals.DB, COLL);
        request.setAnalyze(true);
        request.setAggregationSteps(
                List.of(between(100, 600), new SortAggregationStep(FIELD, true), new LimitAggregationStep(5)));

        final var result = ((AggregateAnalyzeResponse) processor.processMessage(request)).getAnalyzeResult();

        assertEquals(List.of(FIELD), result.getIndexesUsed());
        assertEquals(5, result.getDocumentsScanned());
    }
}
