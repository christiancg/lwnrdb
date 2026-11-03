package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class RefusedSortIndexTopKTest {
    private static final String DB = TestGlobals.DB;
    private static final String COLL = TestGlobals.COLL;
    private static final int SMALL_INDEX_DOCUMENTS = 2000;
    private static final int LARGE_INDEX_DOCUMENTS = 20000;
    private static final String SHORT_ID = "d%04d";
    private static final String LONG_ID = "d%047d";

    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
    }

    private void load(int documents, String idFormat) throws Exception {
        final var entries = new ArrayList<DbEntry>(documents);
        for (var i = 0; i < documents; i++) {
            final var data = new JsonObject();
            data.addProperty(Globals.PK_FIELD, String.format(idFormat, i));
            data.addProperty("cat", "c" + i % 10);
            data.addProperty("score", (i * 7919) % documents);
            entries.add(DbEntry.fromJsonObject(DB, COLL, data));
        }
        fs.bulkInsertIntoCollection(DB, COLL, entries);
        IndexHelper.createIndex(DB, COLL, "cat");
        IndexHelper.createIndex(DB, COLL, "score");
        cache.getAdminCollectionEntry(DB, COLL).setIndexes(Set.of("cat", "score"));
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(userCache, "fs", fs);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private List<String> ids(BaseAggregationStep... steps) {
        final var request = new AggregateRequest(DB, COLL);
        request.setAggregationSteps(List.of(steps));
        final var response = IocContainer.get(OperationProcessor.class).processMessage(request);
        return assertInstanceOf(AggregateResponse.class, response, response.getMessage()).getResults().stream()
                .map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    private FileSystem refuseEveryIndexAndSpy() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L);
        cache.evictCollection(DB, COLL);
        final var spied = spy(fs);
        TestUtils.setPrivateField(userCache, "fs", spied);
        return spied;
    }

    private List<String> topK(boolean forcedScan) {
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "cat", new JsonString("c3")));
        final var rest = List.of(new SortAggregationStep("score", true), new SkipAggregationStep(100),
                new LimitAggregationStep(20));
        final var steps = new ArrayList<BaseAggregationStep>();
        if (forcedScan) {
            steps.add(new SkipAggregationStep(0));
        }
        steps.add(filter);
        steps.addAll(rest);
        return ids(steps.toArray(BaseAggregationStep[]::new));
    }

    @Test
    void aSmallSortIndexTheCacheCannotHoldIsStillWalked() throws Exception {
        load(SMALL_INDEX_DOCUMENTS, SHORT_ID);
        final var scanned = topK(true);
        assertTrue(fs.indexFileBytes(DB, COLL, "score", "Number") <= Globals.TRANSIENT_SORT_WALK_MAX_INDEX_BYTES);
        final var spied = refuseEveryIndexAndSpy();

        assertEquals(scanned, topK(false));
        assertEquals(20, scanned.size());
        verify(spied, atLeastOnce()).readWholeFieldIndexFiles(eq(DB), eq(COLL), eq("score"), eq(Number.class));
    }

    @Test
    void aLargeSortIndexTheCacheCannotHoldIsNeverWalked() throws Exception {
        load(LARGE_INDEX_DOCUMENTS, LONG_ID);
        final var scanned = topK(true);
        assertTrue(fs.indexFileBytes(DB, COLL, "score", "Number") > Globals.TRANSIENT_SORT_WALK_MAX_INDEX_BYTES);
        final var spied = refuseEveryIndexAndSpy();

        for (var run = 0; run < 3; run++) {
            assertEquals(scanned, topK(false));
        }
        assertEquals(20, scanned.size());
        verify(spied, never()).readWholeFieldIndexFiles(eq(DB), eq(COLL), eq("score"), eq(Number.class));
    }
}
