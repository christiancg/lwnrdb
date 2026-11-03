package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class LazyIndexSourcesTest {
    private static final String COLL = "lazysources";
    private static final int DOCUMENTS = 3000;
    private static final int CATEGORIES = 3;
    private static final int REGIONS = 7;
    private static final int FIRST_CHUNK = Globals.LAZY_CANDIDATE_CHUNKS.getFirst();

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    private record Answer(List<String> ids, long scanned) {
    }

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLL));
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            documents.add(document(String.format("d%04d", i), i % CATEGORIES, i % REGIONS, (i * 7919) % DOCUMENTS));
        }
        final var bulk = new BulkSaveRequest(TestGlobals.DB, COLL);
        bulk.setObjects(documents);
        processor.processMessage(bulk);
        for (final var field : List.of("cat", "ts", "uid")) {
            processor.processMessage(new CreateIndexRequest(TestGlobals.DB, COLL, field));
        }
        drainBackgroundWrites();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException, IOException, InterruptedException {
        TestUtils.releaseAllLocks();
        processor.processMessage(new DropCollectionRequest(TestGlobals.DB, COLL));
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, int cat, int region, int ts) {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString(id));
        document.add("cat", new JsonString("c" + cat));
        document.add("region", new JsonString("r" + region));
        document.add("ts", new JsonNumber(ts));
        document.add("uid", new JsonString("u" + id));
        return document;
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

    private Answer run(UUID client, boolean dirty, BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, COLL);
        request.setAnalyze(true);
        request.setDirtyRead(dirty);
        request.setAggregationSteps(List.of(steps));
        final var response = client == null
                ? processor.processMessage(request)
                : processor.processMessage(request, client);
        final var analyzed = assertInstanceOf(AggregateAnalyzeResponse.class, response, response.getMessage());
        return new Answer(
                analyzed.getResults().stream().map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue()).toList(),
                analyzed.getAnalyzeResult().getDocumentsScanned());
    }

    private Answer run(BaseAggregationStep... steps) {
        return run(null, false, steps);
    }

    private List<String> scanned(BaseAggregationStep... steps) {
        final var prefixed = new ArrayList<BaseAggregationStep>();
        prefixed.add(new SkipAggregationStep(0));
        prefixed.addAll(List.of(steps));
        return run(prefixed.toArray(BaseAggregationStep[]::new)).ids();
    }

    private static FieldOperator equalsTo(String field, String value) {
        return new FieldOperator(FieldOperatorType.EQUALS, field, new JsonString(value));
    }

    private static FilterAggregationStep filter(BaseOperator operator) {
        return new FilterAggregationStep(operator);
    }

    private static FilterAggregationStep and(BaseOperator... legs) {
        return filter(new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(legs)));
    }

    private static MapAggregationStep doubledTs() {
        final var operands = new JsonArray();
        operands.add(new JsonString("ts"));
        operands.add(new JsonNumber(2));
        return new MapAggregationStep(List.of(new AddFieldMapOperator("twice", null,
                new ArrayParamMidOperator(MidOperationType.MULTIPLY, operands))));
    }

    private void save(JsonObject document, UUID client) {
        final var request = new SaveRequest(TestGlobals.DB, COLL);
        request.setObject(document);
        final var response = client == null
                ? processor.processMessage(request)
                : processor.processMessage(request, client);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }

    @Test
    public void filterThenMapThenLimitFetchesOneChunk() {
        final var all = scanned(filter(equalsTo("cat", "c1")));
        for (final var source : List.of(filter(equalsTo("cat", "c1")),
                and(equalsTo("cat", "c1"), equalsTo("region", "r2")))) {
            final var answer = run(source, doubledTs(), new LimitAggregationStep(5));

            assertEquals(5, Set.copyOf(answer.ids()).size());
            assertTrue(all.containsAll(answer.ids()));
            assertTrue(answer.scanned() <= FIRST_CHUNK, source.getOperator() + " read " + answer.scanned());
        }
    }

    @Test
    public void conjunctionWithUnindexedLegStopsAtLimit() {
        final var both = and(equalsTo("cat", "c1"), equalsTo("region", "r2"));
        final var all = scanned(both);

        final var answer = run(both, new LimitAggregationStep(5));
        final var filteredTwice = run(filter(equalsTo("cat", "c1")), filter(equalsTo("region", "r2")),
                new LimitAggregationStep(5));

        assertEquals(5, answer.ids().size());
        assertTrue(all.containsAll(answer.ids()));
        assertEquals(FIRST_CHUNK, answer.scanned(), "the first chunk of the 1000 candidates holds five matches");
        assertTrue(all.containsAll(filteredTwice.ids()));
        assertTrue(filteredTwice.scanned() < DOCUMENTS / CATEGORIES, "read " + filteredTwice.scanned());
        assertEquals(DOCUMENTS / CATEGORIES, run(null, true, both, new LimitAggregationStep(5)).scanned(),
                "a dirty read holds no collection lock, so it keeps the one-shot fetch");
    }

    @Test
    public void filterOtherFieldSortIndexedLimitWalksIndexOrder() {
        final var shapes = List.of(filter(equalsTo("cat", "c1")), and(equalsTo("cat", "c2"), equalsTo("region", "r3")),
                and(equalsTo("cat", "c0"),
                        new FieldOperator(FieldOperatorType.GREATER_THAN, "ts", new JsonNumber(1500))),
                and(new FieldOperator(FieldOperatorType.SMALLER_THAN_EQUALS, "ts", new JsonNumber(2000)),
                        equalsTo("region", "r1")),
                and(equalsTo("cat", "c2"), equalsTo("region", "r4"),
                        new FieldOperator(FieldOperatorType.GREATER_THAN, "ts", new JsonNumber(100))));
        for (final var shape : shapes) {
            for (final var ascending : List.of(true, false)) {
                for (final var skip : List.of(0, 7)) {
                    final var sort = new SortAggregationStep("ts", ascending);
                    final var steps = skip == 0
                            ? new BaseAggregationStep[]{shape, sort, new LimitAggregationStep(5)}
                            : new BaseAggregationStep[]{shape, sort, new SkipAggregationStep(skip),
                                    new LimitAggregationStep(5)};
                    final var answer = run(steps);
                    final var label = shape.getOperator() + " ascending=" + ascending + " skip=" + skip;

                    assertEquals(scanned(steps), answer.ids(), label);
                    assertTrue(answer.scanned() < 5L + skip + FIRST_CHUNK, label + " read " + answer.scanned());
                }
            }
        }
        assertEquals(5,
                run(filter(equalsTo("cat", "c1")), new SortAggregationStep("ts", false), new LimitAggregationStep(5))
                        .scanned(),
                "an exact index leg fetches only the returned rows");
    }

    @Test
    public void aNarrowFilterKeepsFetchingItsMatches() {
        final var steps = new BaseAggregationStep[]{filter(equalsTo("uid", "ud0042")),
                new SortAggregationStep("ts", true), new LimitAggregationStep(5)};

        final var answer = run(steps);

        assertEquals(List.of("d0042"), answer.ids());
        assertEquals(scanned(steps), answer.ids());
        assertEquals(1, answer.scanned());
    }

    @Test
    public void aConjunctionMatchingNothingPullsEveryChunk() {
        final var answer = run(and(equalsTo("cat", "c1"), equalsTo("region", "r9")), new LimitAggregationStep(5));

        assertEquals(List.of(), answer.ids());
        assertEquals(DOCUMENTS / CATEGORIES, answer.scanned());
    }

    @Test
    public void aSortWalkOverNoCandidatesAnswersEmpty() {
        final var answer = run(filter(equalsTo("cat", "none")), new SortAggregationStep("ts", true),
                new LimitAggregationStep(5));

        assertEquals(List.of(), answer.ids());
        assertEquals(0, answer.scanned());
    }

    @Test
    public void aWalkPastItsBudgetFallsBackToTheMatches() throws IOException, InterruptedException {
        final var rare = 40;
        for (var i = 0; i < rare; i++) {
            final var document = document("rare" + i, 0, 0, DOCUMENTS + i);
            document.add("cat", new JsonString("rare"));
            save(document, null);
        }
        drainBackgroundWrites();
        final var steps = new BaseAggregationStep[]{filter(equalsTo("cat", "rare")),
                new SortAggregationStep("ts", true), new LimitAggregationStep(5)};

        final var answer = run(steps);

        assertEquals(List.of("rare0", "rare1", "rare2", "rare3", "rare4"), answer.ids());
        assertEquals(scanned(steps), answer.ids());
        assertEquals(rare, answer.scanned(), "the walk gave up before any member and the matches were fetched");
    }

    @Test
    public void aLimitAboveTheWalkCapKeepsTheCurrentPath() {
        final var steps = new BaseAggregationStep[]{filter(equalsTo("cat", "c1")), new SortAggregationStep("ts", true),
                new LimitAggregationStep(5000)};

        final var answer = run(steps);

        assertEquals(scanned(steps), answer.ids());
        assertEquals(DOCUMENTS / CATEGORIES, answer.scanned());
    }

    @Test
    public void deepSkipDropsIdsBeforeFetch() {
        final var sorted = new BaseAggregationStep[]{new SortAggregationStep("ts", true), new SkipAggregationStep(2000),
                new LimitAggregationStep(5)};
        final var sortedAnswer = run(sorted);
        assertEquals(scanned(sorted), sortedAnswer.ids());
        assertEquals(5, sortedAnswer.scanned());

        final var cat = filter(equalsTo("cat", "c1"));
        final var head = run(cat, new LimitAggregationStep(505)).ids();
        final var window = run(cat, new SkipAggregationStep(500), new LimitAggregationStep(5));
        assertEquals(head.subList(500, 505), window.ids());
        assertEquals(5, window.scanned());
    }

    @Test
    public void skipPastTheMatchCountReturnsEmptyWithoutFetching() {
        final var cat = filter(equalsTo("cat", "c1"));
        final var shapes = List.of(
                new BaseAggregationStep[]{cat, new SkipAggregationStep(5000), new LimitAggregationStep(5)},
                new BaseAggregationStep[]{new SortAggregationStep("ts", false), new SkipAggregationStep(5000),
                        new LimitAggregationStep(5)},
                new BaseAggregationStep[]{cat, new SortAggregationStep("ts", false), new SkipAggregationStep(5000),
                        new LimitAggregationStep(5)});
        for (final var steps : shapes) {
            final var answer = run(steps);

            assertEquals(List.of(), answer.ids());
            assertEquals(0, answer.scanned());
        }
    }

    @Test
    public void pendingWritesFallBackToCurrentPaths() {
        save(document("late", 1, 2, 1), null);
        save(document("d0001", 1, 2, 2999), null);
        assertFalse(IocContainer.get(PendingIndexWrites.class).idsFor(TestGlobals.DB, COLL).isEmpty());
        final var cat = filter(equalsTo("cat", "c1"));
        final var sortedSteps = List.of(
                new BaseAggregationStep[]{cat, new SortAggregationStep("ts", true), new LimitAggregationStep(5)},
                new BaseAggregationStep[]{cat, new SortAggregationStep("ts", false), new SkipAggregationStep(3),
                        new LimitAggregationStep(5)},
                new BaseAggregationStep[]{new SortAggregationStep("ts", true), new SkipAggregationStep(10),
                        new LimitAggregationStep(5)});
        for (final var steps : sortedSteps) {
            assertEquals(scanned(steps), run(steps).ids());
        }
        final var window = run(cat, new SkipAggregationStep(10), new LimitAggregationStep(5));
        assertEquals(5, Set.copyOf(window.ids()).size());
        assertTrue(scanned(cat).containsAll(window.ids()));
        assertTrue(window.scanned() > 5, "pending ids are fetched before the walk, so the skip is not dropped");
    }

    @Test
    public void aTransactionOverlayKeepsTheCurrentPath() {
        final var client = BeforeHookTestSupport.newClient();
        try {
            assertEquals(OperationStatus.OK,
                    processor.processMessage(new StartTransactionRequest(), client).getStatus());
            save(document("buffered", 1, 9, -1), client);

            final var conjunction = run(client, false, and(equalsTo("cat", "c1"), equalsTo("region", "r9")),
                    new LimitAggregationStep(5));
            final var sorted = run(client, false, filter(equalsTo("cat", "c1")), new SortAggregationStep("ts", true),
                    new LimitAggregationStep(2));

            assertEquals(List.of("buffered"), conjunction.ids());
            assertEquals("buffered", sorted.ids().getFirst());
            assertEquals(List.of(), run(and(equalsTo("cat", "c1"), equalsTo("region", "r9"))).ids());
        } finally {
            processor.processMessage(new RollbackTransactionRequest(), client);
        }
    }
}
