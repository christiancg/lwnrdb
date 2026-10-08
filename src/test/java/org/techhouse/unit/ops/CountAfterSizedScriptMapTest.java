package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.BaseMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CountAfterSizedScriptMapTest {
    private static final String FIELD = "score";
    private static final String THROWING_SCRIPT = "export default () => { throw new Error('boom'); }";
    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        cache = IocContainer.get(Cache.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void insert(String id, int score) throws IOException {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add(FIELD, new JsonNumber(score));
        object.add("ref", new JsonNumber(score));
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void seed() throws IOException {
        insert("a", 1);
        insert("b", 2);
        insert("c", 3);
    }

    private void indexField() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new HashSet<>(entry.getIndexes());
        indexes.add(FIELD);
        entry.setIndexes(indexes);
    }

    private static MapAggregationStep map(BaseMidOperator operation) {
        return new MapAggregationStep(List.of(new AddFieldMapOperator("extra", null, operation)));
    }

    private static MapAggregationStep throwingMap() {
        return map(new ScriptMidOperator(THROWING_SCRIPT));
    }

    private static SortAggregationStep sortByScore() {
        return new SortAggregationStep(FIELD, true);
    }

    private static List<JsonObject> run(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request);
    }

    private static int countOf(BaseAggregationStep... steps) throws IOException {
        return run(steps).getFirst().get("count").asJsonNumber().asInteger();
    }

    @Test
    public void test_count_after_a_scan_sort_runs_a_throwing_map_script() throws Exception {
        seed();

        assertThrows(ScriptCallableException.class,
                () -> run(sortByScore(), throwingMap(), new CountAggregationStep()));
    }

    @Test
    public void test_count_after_an_indexed_and_a_scanned_sort_agree() throws Exception {
        seed();
        assertThrows(ScriptCallableException.class,
                () -> run(sortByScore(), throwingMap(), new CountAggregationStep()));

        indexField();

        assertThrows(ScriptCallableException.class,
                () -> run(sortByScore(), throwingMap(), new CountAggregationStep()));
    }

    @Test
    public void test_count_after_a_join_runs_a_throwing_map_script() throws Exception {
        seed();

        assertThrows(ScriptCallableException.class,
                () -> run(new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined"), throwingMap(),
                        new CountAggregationStep()));
    }

    @Test
    public void test_count_after_group_by_runs_a_throwing_map_script() throws Exception {
        seed();

        assertThrows(ScriptCallableException.class,
                () -> run(new GroupByAggregationStep(FIELD), throwingMap(), new CountAggregationStep()));
    }

    @Test
    public void test_count_after_sort_skip_and_limit_counts_the_sliced_rows() throws Exception {
        seed();

        assertEquals(2, countOf(sortByScore(), new SkipAggregationStep(1), new LimitAggregationStep(2),
                new CountAggregationStep()));
    }

    @Test
    public void test_count_after_a_non_script_map_is_unchanged() throws Exception {
        seed();
        final var sumOfScore = new ArrayParamMidOperator(MidOperationType.SUM, scoreField());

        assertEquals(3, countOf(sortByScore(), map(sumOfScore), new CountAggregationStep()));
    }

    @Test
    public void test_count_after_a_throwing_map_over_an_empty_sorted_collection_is_zero() throws Exception {
        assertEquals(0, countOf(sortByScore(), throwingMap(), new CountAggregationStep()));
    }

    private static JsonArray scoreField() {
        final var fields = new JsonArray();
        fields.add(new JsonString(FIELD));
        return fields;
    }
}
