package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UsageTracker;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexedFilterLimitTest {
    private static final String FIRST_ID = "d00000";
    private static final String COLL = "complementLimit";
    private static final int DOCUMENTS = 5_000;
    private static final int LIMIT = 10;
    private static final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final UsageTracker usage = IocContainer.get(UsageTracker.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pending = IocContainer.get(PendingIndexWrites.class);

    @BeforeAll
    static void seed() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLL));
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var doc = new JsonObject();
            doc.add(Globals.PK_FIELD, new JsonString(String.format("d%05d", i)));
            doc.add("num", new JsonNumber(i));
            doc.add("mixed", i % 2 == 0 ? new JsonNumber(i % 10) : new JsonString("s" + i % 10));
            objects.add(doc);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.DB, COLL);
        bulk.setObjects(objects);
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());
        for (final var field : List.of("num", "mixed")) {
            assertEquals(OperationStatus.OK,
                    processor.processMessage(new CreateIndexRequest(TestGlobals.DB, COLL, field)).getStatus());
        }
        pending.clearCollection(TestGlobals.DB, COLL);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static FilterAggregationStep filter(FieldOperatorType type, String field, JsonBaseElement value) {
        return new FilterAggregationStep(new FieldOperator(type, field, value));
    }

    private static JsonArray numbers(int count) {
        final var array = new JsonArray();
        for (var i = 0; i < count; i++) {
            array.add(new JsonNumber(i));
        }
        return array;
    }

    private static AggregateAnalyzeResponse analyzed(List<BaseAggregationStep> steps) {
        final var request = new AggregateRequest(TestGlobals.DB, COLL);
        request.setAnalyze(true);
        request.setAggregationSteps(steps);
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        return response;
    }

    private static Set<String> idsOf(AggregateAnalyzeResponse response) {
        final var ids = new HashSet<String>();
        for (final var row : response.getResults()) {
            assertTrue(ids.add(row.get(Globals.PK_FIELD).asJsonString().getValue()), "no row is returned twice");
        }
        return ids;
    }

    private static Set<String> scanned(List<BaseAggregationStep> steps) {
        final var forced = new ArrayList<BaseAggregationStep>();
        forced.add(new SkipAggregationStep(0));
        forced.addAll(steps);
        return idsOf(analyzed(forced));
    }

    private static long numAccesses() {
        final var counter = usage.getCounter(AccessKind.FIELD_INDEX, TestGlobals.DB, COLL, "num");
        return counter == null ? 0 : counter.getAccessCount();
    }

    @Test
    public void test_a_broad_not_equals_declines_to_the_scan() {
        final var steps = List.of(filter(FieldOperatorType.NOT_EQUALS, "num", new JsonNumber(7)),
                new LimitAggregationStep(LIMIT));

        final var response = analyzed(steps);

        final var ids = idsOf(response);
        assertEquals(LIMIT, ids.size());
        assertTrue(scanned(List.of(steps.getFirst())).containsAll(ids), "every row is one the scan returns");
        assertTrue(response.getAnalyzeResult().getDocumentsScanned() <= 2L * LIMIT,
                "the scan stops at the limit instead of fetching the whole complement: "
                        + response.getAnalyzeResult().getDocumentsScanned());
        assertTrue(response.getAnalyzeResult().getIndexesUsed().contains("num"),
                "the count that chose the scan consulted the index");
    }

    private static void moveFirstIdBetweenEntries(double from, double to) throws Exception {
        locks.lockRead(TestGlobals.DB, COLL);
        try {
            final var entries = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, COLL, "num", Number.class);
            entries.stream().filter(entry -> entry.getValue().doubleValue() == from).findFirst().orElseThrow().getIds()
                    .remove(FIRST_ID);
            entries.stream().filter(entry -> entry.getValue().doubleValue() == to).findFirst().orElseThrow().getIds()
                    .add(FIRST_ID);
        } finally {
            locks.releaseRead(TestGlobals.DB, COLL);
        }
    }

    @Test
    public void test_a_broad_complement_with_a_limit_is_answered_by_the_scan_not_the_index() throws Exception {
        final var steps = List.of(filter(FieldOperatorType.NOT_EQUALS, "num", new JsonNumber(7)),
                new LimitAggregationStep(DOCUMENTS));
        moveFirstIdBetweenEntries(0, 7);
        final Set<String> ids;
        try {
            ids = idsOf(analyzed(steps));
        } finally {
            moveFirstIdBetweenEntries(7, 0);
        }

        assertEquals(DOCUMENTS - 1, ids.size(), "an index complement would have dropped the planted id too");
        assertTrue(ids.contains(FIRST_ID));
    }

    @Test
    public void test_a_broad_not_in_declines_to_a_scan_source_that_keeps_the_later_steps() throws Exception {
        final var steps = List.of(filter(FieldOperatorType.NOT_IN, "num", numbers(3)), new SkipAggregationStep(4),
                new LimitAggregationStep(LIMIT));
        locks.lockRead(TestGlobals.DB, COLL);
        final IndexedSource.Fast fast;
        final List<JsonObject> rows;
        try (var context = PipelineContext.of(0L, Long.MAX_VALUE, new AnalyzeContext())) {
            fast = IndexedFilterLimit.tryLazy(steps, TestGlobals.DB, COLL, context);
            assertNotNull(fast);
            rows = fast.stream().toList();
        } finally {
            locks.releaseRead(TestGlobals.DB, COLL);
        }

        assertEquals(1, fast.nextStepIndex(), "the SKIP is left to its own step");
        assertTrue(rows.stream().noneMatch(row -> row.get("num").asJsonNumber().getValue().intValue() < 3));
        assertEquals(LIMIT, idsOf(analyzed(steps)).size());
    }

    @Test
    public void test_a_narrow_not_in_stays_indexed() {
        final var steps = List.of(filter(FieldOperatorType.NOT_IN, "num", numbers(DOCUMENTS - 5)),
                new LimitAggregationStep(LIMIT));

        final var response = analyzed(steps);

        assertEquals(Set.of("d04995", "d04996", "d04997", "d04998", "d04999"), idsOf(response));
        assertEquals(5L, response.getAnalyzeResult().getDocumentsScanned(),
                "only the complement's documents are fetched");
    }

    @Test
    public void test_the_complement_count_runs_once() {
        final var steps = List.of(filter(FieldOperatorType.NOT_IN, "num", numbers(100)),
                new LimitAggregationStep(LIMIT));
        analyzed(steps);
        final var before = numAccesses();

        analyzed(steps);

        assertEquals(1L, numAccesses() - before, "the scan decision reads the index once per query");
    }

    @Test
    public void test_complements_with_null_object_and_duplicate_operands_answer_what_the_scan_answers() {
        final var withNull = numbers(3);
        withNull.add(JsonNull.INSTANCE);
        final var duplicates = numbers(DOCUMENTS - 5);
        duplicates.addAll(numbers(DOCUMENTS - 5));
        final var objects = new JsonArray();
        objects.add(new JsonObject());
        final List<FilterAggregationStep> filters = List.of(filter(FieldOperatorType.NOT_IN, "num", withNull),
                filter(FieldOperatorType.NOT_IN, "num", objects),
                filter(FieldOperatorType.NOT_EQUALS, "num", new JsonObject()),
                filter(FieldOperatorType.NOT_IN, "num", duplicates),
                filter(FieldOperatorType.NOT_EQUALS, "num", JsonNull.INSTANCE));
        for (final var step : filters) {
            final var all = scanned(List.of(step));
            final var limited = idsOf(analyzed(List.of(step, new LimitAggregationStep(LIMIT))));

            assertEquals(Math.min(LIMIT, all.size()), limited.size(), step.toString());
            assertTrue(all.containsAll(limited), step.toString());
        }
    }

    @Test
    public void test_a_field_with_two_index_types_answers_complements_like_the_scan() {
        final var steps = List.<BaseAggregationStep>of(filter(FieldOperatorType.NOT_IN, "mixed", numbers(5)));
        final var all = scanned(steps);
        final var limited = analyzed(List.of(steps.getFirst(), new LimitAggregationStep(LIMIT)));

        assertEquals(DOCUMENTS / 2 + DOCUMENTS / 5, all.size());
        assertEquals(LIMIT, idsOf(limited).size());
        assertTrue(all.containsAll(idsOf(limited)));
        assertEquals(all, idsOf(analyzed(steps)));
    }
}
