package org.techhouse.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.concurrency.SettleWindow;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.PointServablePipeline;
import org.techhouse.ops.admin.ReadPathHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReadPathHelperPointModeTest {
    private static final long WAIT_MS = 5_000L;
    private static final long BLOCKED_MS = 300L;
    private static final String OTHER = "point_other";
    private static final String ID = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, OTHER)).getStatus());
        for (var number = 0; number < 9; number++) {
            save(TestGlobals.COLL, "d" + number, new JsonString("c" + number % 3));
        }
        save(OTHER, "o1", new JsonString("c1"));
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "cat")).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String coll, String id, JsonBaseElement cat) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("cat", cat);
        final var request = new SaveRequest(TestGlobals.DB, coll);
        request.setObject(document);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private static AggregateRequest request(BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return request;
    }

    private static FilterAggregationStep filter(FieldOperatorType type, String field, JsonBaseElement value) {
        return new FilterAggregationStep(new FieldOperator(type, field, value));
    }

    private static FilterAggregationStep catIs(String value) {
        return filter(FieldOperatorType.EQUALS, "cat", new JsonString(value));
    }

    private static CompletableFuture<OperationResponse> inBackground(AggregateRequest request) {
        return CompletableFuture.supplyAsync(() -> ReadPathHelper.processAggregateOperation(request, null),
                command -> Thread.ofVirtual().start(command));
    }

    private SettleWindow openWindow() throws InterruptedException {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        final var window = locks.openSettleWindow(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(window.shared());
        return window;
    }

    private static List<JsonObject> rows(OperationResponse response) {
        return assertInstanceOf(AggregateResponse.class, response).getResults();
    }

    private static JsonArray ids(String... ids) {
        final var array = new JsonArray();
        for (final var id : ids) {
            array.add(new JsonString(id));
        }
        return array;
    }

    @Test
    public void test_index_served_pipelines_pass_an_open_settle_window() throws Exception {
        try (var ignored = openWindow()) {
            final var limited = inBackground(request(catIs("c1"), new LimitAggregationStep(2)));
            final var byId = inBackground(request(filter(FieldOperatorType.EQUALS, "_id", new JsonString("d4"))));
            final var byIds = inBackground(request(filter(FieldOperatorType.IN, "_id", ids("d1", "d2", "nope"))));
            final var counted = inBackground(request(catIs("c2"), new CountAggregationStep()));
            final var countAll = inBackground(request(new CountAggregationStep()));

            final var limitedRows = rows(limited.get(WAIT_MS, TimeUnit.MILLISECONDS));
            assertEquals(2, limitedRows.size());
            assertTrue(limitedRows.stream().allMatch(row -> "c1".equals(row.get("cat").asJsonString().getValue())));
            assertEquals("d4",
                    rows(byId.get(WAIT_MS, TimeUnit.MILLISECONDS)).getFirst().get("_id").asJsonString().getValue());
            assertEquals(2, rows(byIds.get(WAIT_MS, TimeUnit.MILLISECONDS)).size());
            assertEquals(3L, rows(counted.get(WAIT_MS, TimeUnit.MILLISECONDS)).getFirst().get("count").asJsonNumber()
                    .getValue().longValue());
            assertEquals(9L, rows(countAll.get(WAIT_MS, TimeUnit.MILLISECONDS)).getFirst().get("count").asJsonNumber()
                    .getValue().longValue());
        }
        assertEquals(0, locks.longReaders(ID));
        assertFalse(locks.holdsCollectionLock(TestGlobals.DB, TestGlobals.COLL));
    }

    private static FilterAggregationStep catInMixedKinds() {
        final var operands = ids("c1");
        operands.add(new JsonNumber(5));
        return filter(FieldOperatorType.IN, "cat", operands);
    }

    @Test
    public void test_a_declining_index_reruns_once_under_read_and_waits_for_the_window() throws Exception {
        final var aggregate = request(catInMixedKinds(), new LimitAggregationStep(5));
        assertTrue(PointServablePipeline.test(aggregate));
        final CompletableFuture<OperationResponse> limited;
        try (var ignored = openWindow()) {
            limited = inBackground(aggregate);
            assertThrows(TimeoutException.class, () -> limited.get(BLOCKED_MS, TimeUnit.MILLISECONDS),
                    "a point hold that would scan reruns under READ, which waits for the window");
        }
        final var found = rows(limited.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals(List.of("d1", "d4", "d7"),
                found.stream().map(row -> row.get("_id").asJsonString().getValue()).sorted().toList());
        assertEquals(0, locks.longReaders(ID));
    }

    @Test
    public void test_a_rerun_reports_one_analysis() {
        final var aggregate = request(catInMixedKinds(), new LimitAggregationStep(5));
        aggregate.setAnalyze(true);

        final var analyzed = assertInstanceOf(AggregateAnalyzeResponse.class,
                ReadPathHelper.processAggregateOperation(aggregate, null));

        assertEquals(3, analyzed.getResults().size());
        assertEquals(List.of(ID),
                analyzed.getAnalyzeResult().getLocksAcquired().stream().filter(lock -> lock.equals(ID)).toList(),
                "the abandoned point attempt left no trace");
    }

    @Test
    public void test_a_join_pipeline_never_takes_a_point_hold() throws Exception {
        final var join = request(catIs("c1"), new LimitAggregationStep(2),
                new JoinAggregationStep(OTHER, "cat", "cat", "others"));
        assertFalse(PointServablePipeline.test(join));
        final CompletableFuture<OperationResponse> joined;
        try (var ignored = openWindow()) {
            joined = inBackground(join);
            assertThrows(TimeoutException.class, () -> joined.get(BLOCKED_MS, TimeUnit.MILLISECONDS));
        }
        final var found = rows(joined.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(row -> row.get("others").asJsonArray().size() == 1));
    }

    @Test
    public void test_only_index_or_pk_served_sources_are_point_servable() {
        assertTrue(PointServablePipeline.test(request(new CountAggregationStep())));
        assertTrue(PointServablePipeline
                .test(request(catIs("c1"), new SkipAggregationStep(1), new LimitAggregationStep(2))));
        assertTrue(PointServablePipeline
                .test(request(filter(FieldOperatorType.IN, "cat", ids("c1", "c2")), new CountAggregationStep())));
        assertTrue(PointServablePipeline.test(request(filter(FieldOperatorType.IN, "_id", ids("d1")))));
        assertFalse(PointServablePipeline.test(request(catIs("c1"))), "an unbounded indexed filter may scan");
        assertFalse(PointServablePipeline.test(
                request(filter(FieldOperatorType.EQUALS, "other", new JsonString("x")), new LimitAggregationStep(1))),
                "no index on the field");
        assertFalse(
                PointServablePipeline.test(request(filter(FieldOperatorType.NOT_EQUALS, "_id", new JsonString("d1")))),
                "a complement walks the whole pk list");
        assertFalse(PointServablePipeline.test(
                request(filter(FieldOperatorType.CONTAINS, "cat", new JsonString("c")), new LimitAggregationStep(1))));
        assertFalse(PointServablePipeline.test(request(new SkipAggregationStep(1), new LimitAggregationStep(1))));
        assertFalse(PointServablePipeline.test(request()));
    }
}
