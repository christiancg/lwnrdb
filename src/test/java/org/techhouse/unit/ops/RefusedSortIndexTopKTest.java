package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final int DOCUMENTS = 2000;

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
        final var entries = new ArrayList<DbEntry>(DOCUMENTS);
        for (var i = 0; i < DOCUMENTS; i++) {
            final var data = new JsonObject();
            data.addProperty(Globals.PK_FIELD, String.format("d%04d", i));
            data.addProperty("cat", "c" + i % 10);
            data.addProperty("score", (i * 7919) % DOCUMENTS);
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

    @Test
    void aFilteredTopKNeverLoadsASortIndexTheCacheCannotHold() throws Exception {
        final var scanned = ids(new SkipAggregationStep(0),
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "cat", new JsonString("c3"))),
                new SortAggregationStep("score", true), new SkipAggregationStep(100), new LimitAggregationStep(20));
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L);
        cache.evictCollection(DB, COLL);
        final var spied = spy(fs);
        TestUtils.setPrivateField(userCache, "fs", spied);

        for (var run = 0; run < 3; run++) {
            assertEquals(scanned,
                    ids(new FilterAggregationStep(
                            new FieldOperator(FieldOperatorType.EQUALS, "cat", new JsonString("c3"))),
                            new SortAggregationStep("score", true), new SkipAggregationStep(100),
                            new LimitAggregationStep(20)));
        }

        assertEquals(20, scanned.size());
        verify(spied, never()).readWholeFieldIndexFiles(eq(DB), eq(COLL), eq("score"), eq(Number.class));
    }
}
