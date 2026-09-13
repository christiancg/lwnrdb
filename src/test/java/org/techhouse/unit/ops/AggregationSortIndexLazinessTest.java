package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

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
}
