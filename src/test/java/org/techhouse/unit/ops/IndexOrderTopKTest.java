package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.techhouse.bckg_ops.PendingIndexWrites;
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
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexOrderTopKTest {
    private static final String COLL = "topkwalk";
    private static final int LIMIT = 5;
    private static final long BUDGET = 64L * LIMIT;
    private static final int LARGE_INDEX_DOCUMENTS = 20000;

    private final TopKFixture fixture = new TopKFixture(COLL);
    private final Configuration configuration = Configuration.getInstance();
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private Long savedMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        fixture.createIndexedOnTs(TopKFixture.standardDocuments());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(userCache, "fs", fs);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        fixture.drop();
        TestUtils.standardTearDown();
    }

    private static BaseAggregationStep[] topK(FilterAggregationStep filter, boolean ascending, int skip) {
        final var sort = new SortAggregationStep("ts", ascending);
        return skip == 0
                ? new BaseAggregationStep[]{filter, sort, new LimitAggregationStep(LIMIT)}
                : new BaseAggregationStep[]{filter, sort, new SkipAggregationStep(skip),
                        new LimitAggregationStep(LIMIT)};
    }

    private static FilterAggregationStep unindexedConjunction() {
        return new FilterAggregationStep(new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "region", new JsonString("r3")),
                        new FieldOperator(FieldOperatorType.EQUALS, "flag", new JsonString("odd")))));
    }

    @Test
    public void test_an_unindexed_filter_walks_the_sort_index_within_budget() {
        for (final var filter : List.of(TopKFixture.regionEquals("r2"), unindexedConjunction())) {
            for (final var ascending : List.of(true, false)) {
                for (final var skip : List.of(0, 9)) {
                    final var steps = topK(filter, ascending, skip);
                    final var label = filter.getOperator() + " ascending=" + ascending + " skip=" + skip;

                    final var answer = fixture.run(steps);

                    assertEquals(fixture.scanned(steps), answer.ids(), label);
                    assertEquals(LIMIT, answer.ids().size(), label);
                    assertTrue(answer.scanned() <= BUDGET, label + " read " + answer.scanned());
                }
            }
        }
    }

    @Test
    public void test_a_filter_matching_nothing_declines_at_the_budget() {
        final var steps = topK(TopKFixture.regionEquals("r99"), true, 0);

        final var answer = fixture.run(steps);

        assertEquals(List.of(), answer.ids());
        assertEquals(fixture.scanned(steps), answer.ids());
        assertTrue(answer.scanned() > TopKFixture.DOCUMENTS, "the walk gave up and the collection was scanned");
        assertTrue(answer.scanned() <= TopKFixture.DOCUMENTS + BUDGET, "read " + answer.scanned());
    }

    @Test
    public void test_a_range_with_an_unindexed_leg_has_a_finite_budget() {
        final var filter = new FilterAggregationStep(new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(TopKFixture.tsCompared(FieldOperatorType.GREATER_THAN_EQUALS, 0),
                        new FieldOperator(FieldOperatorType.EQUALS, "region", new JsonString("r99")))));
        final var steps = topK(filter, true, 0);

        final var answer = fixture.run(steps);

        assertEquals(List.of(), answer.ids());
        assertEquals(fixture.scanned(steps), answer.ids());
        assertTrue(answer.scanned() > TopKFixture.DOCUMENTS, "the walk gave up and the collection was scanned");
        assertTrue(answer.scanned() <= TopKFixture.DOCUMENTS + BUDGET, "read " + answer.scanned());
    }

    @Test
    public void test_pending_writes_on_the_sort_field_keep_the_scan_path() {
        fixture.save(TopKFixture.document("late", 2, -1));
        fixture.save(TopKFixture.document("d0002", 2, -2));
        assertFalse(IocContainer.get(PendingIndexWrites.class).idsFor(TestGlobals.DB, COLL).isEmpty());
        final var steps = topK(TopKFixture.regionEquals("r2"), true, 0);

        final var answer = fixture.run(steps);

        assertEquals(fixture.scanned(steps), answer.ids());
        assertEquals(List.of("d0002", "late"), answer.ids().subList(0, 2));
        assertTrue(answer.scanned() >= TopKFixture.DOCUMENTS, "pending ids keep the scan, read " + answer.scanned());
    }

    @Test
    public void test_a_sort_field_holding_several_types_is_walked_in_order() throws Exception {
        for (var i = 0; i < 40; i++) {
            final var document = TopKFixture.document("s" + i, 2, 0);
            document.add("ts", new JsonString("t" + (i % 13)));
            fixture.save(document);
        }
        fixture.drain();
        for (final var ascending : List.of(true, false)) {
            final var steps = topK(TopKFixture.regionEquals("r2"), ascending, 3);

            final var answer = fixture.run(steps);

            assertEquals(fixture.scanned(steps), answer.ids(), "ascending=" + ascending);
            assertTrue(answer.scanned() <= BUDGET, "read " + answer.scanned());
        }
    }

    @Test
    public void test_a_hash_kind_on_the_sort_field_still_declines() throws Exception {
        final var document = TopKFixture.document("nested", 2, 0);
        final var nested = new JsonObject();
        nested.addProperty("k", 1);
        document.add("ts", nested);
        fixture.save(document);
        fixture.drain();
        final var steps = topK(TopKFixture.regionEquals("r2"), true, 0);

        final var answer = fixture.run(steps);

        assertEquals(fixture.scanned(steps), answer.ids());
        assertTrue(answer.scanned() >= TopKFixture.DOCUMENTS, "read " + answer.scanned());
    }

    @Test
    public void test_the_walk_still_declines_when_the_sort_index_would_reload_over_one_megabyte() throws Exception {
        final var entries = new ArrayList<DbEntry>(LARGE_INDEX_DOCUMENTS);
        for (var i = 0; i < LARGE_INDEX_DOCUMENTS; i++) {
            final var data = new JsonObject();
            data.addProperty(Globals.PK_FIELD, String.format("d%047d", i));
            data.addProperty("region", "r" + i % TopKFixture.REGIONS);
            data.addProperty("score", (i * 7919) % LARGE_INDEX_DOCUMENTS);
            entries.add(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data));
        }
        fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "score");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("score"));
        assertTrue(fs.indexFileBytes(TestGlobals.DB, TestGlobals.COLL, "score",
                "Number") > Globals.TRANSIENT_SORT_WALK_MAX_INDEX_BYTES);
        final var large = new TopKFixture(TestGlobals.COLL);
        final var steps = new BaseAggregationStep[]{TopKFixture.regionEquals("r4"),
                new SortAggregationStep("score", true), new LimitAggregationStep(20)};
        final var expected = large.scanned(steps);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L);
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
        final var spied = spy(fs);
        TestUtils.setPrivateField(userCache, "fs", spied);

        assertEquals(expected, large.run(steps).ids());
        assertEquals(20, expected.size());
        verify(spied, never()).readWholeFieldIndexFiles(eq(TestGlobals.DB), eq(TestGlobals.COLL), eq("score"),
                eq(Number.class));
    }
}
