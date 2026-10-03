package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.JsonUtils;

public class AggregationDottedFieldTest {
    private final EJson eJson = new EJson();
    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        cache = IocContainer.get(Cache.class);
        insert(TestGlobals.COLL, "1", "x");
        insert(TestGlobals.COLL, "2", "y");
        insert(TestGlobals.COLL, "3", "x");
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void insert(String collName, String id, String nestedValue) throws IOException {
        final var inner = new JsonObject();
        inner.add("b", new JsonString(nestedValue));
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("a", inner);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, collName, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, collName, entry);
    }

    private void indexNestedField() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "a.b");
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new HashSet<>(entry.getIndexes());
        indexes.add("a.b");
        entry.setIndexes(indexes);
    }

    private List<JsonObject> run(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request);
    }

    private List<String> sortedJson(List<JsonObject> rows) {
        final var json = new ArrayList<String>();
        for (final var row : rows) {
            json.add(eJson.toJson(row));
        }
        json.sort(String::compareTo);
        return json;
    }

    private static FilterAggregationStep equalsX() {
        return new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "a.b", new JsonString("x")));
    }

    @Test
    public void test_group_by_a_dotted_field_emits_nested_on_scan_and_index() throws Exception {
        final var scanned = run(new GroupByAggregationStep("a.b"));
        indexNestedField();
        final var indexed = run(new GroupByAggregationStep("a.b"));

        assertEquals(2, scanned.size());
        for (final var row : scanned) {
            assertFalse(row.has("a.b"));
            assertTrue(JsonUtils.getFromPath(row, "a.b").isJsonString());
        }
        assertEquals(sortedJson(scanned), sortedJson(indexed));
    }

    @Test
    public void test_distinct_a_dotted_field_emits_nested_on_scan_and_index() throws Exception {
        final var scanned = run(new DistinctAggregationStep("a.b"));
        indexNestedField();
        final var indexed = run(new DistinctAggregationStep("a.b"));

        assertEquals(List.of("{\"a\":{\"b\":\"x\"}}", "{\"a\":{\"b\":\"y\"}}"), sortedJson(scanned));
        assertEquals(sortedJson(scanned), sortedJson(indexed));
    }

    @Test
    public void test_group_by_then_filter_on_the_same_dotted_field() throws Exception {
        final var rows = run(new GroupByAggregationStep("a.b"), equalsX());

        assertEquals(1, rows.size());
        assertEquals(2, rows.getFirst().get("group").asJsonArray().size());
    }

    @Test
    public void test_distinct_then_sort_on_a_dotted_field_orders_rows() throws Exception {
        final var rows = run(new DistinctAggregationStep("a.b"), new SortAggregationStep("a.b", false));

        assertEquals("y", JsonUtils.getFromPath(rows.get(0), "a.b").asJsonString().getValue());
        assertEquals("x", JsonUtils.getFromPath(rows.get(1), "a.b").asJsonString().getValue());
    }

    @Test
    public void test_join_as_a_dotted_field_nests_the_array() throws Exception {
        insert(TestGlobals.JOIN_COLL, "r1", "x");
        final var rows = run(equalsX(), new JoinAggregationStep(TestGlobals.JOIN_COLL, "a.b", "a.b", "joined.rows"));

        assertEquals(2, rows.size());
        for (final var row : rows) {
            assertFalse(row.has("joined.rows"));
            assertEquals(1, JsonUtils.getFromPath(row, "joined.rows").asJsonArray().size());
        }
    }

    @Test
    public void test_reduce_with_a_dotted_result_field_nests_the_value() throws Exception {
        final var rows = run(
                new ReduceAggregationStep("export default (acc, doc) => acc + 1;", new JsonNumber(0), "stats.count"));

        assertEquals(1, rows.size());
        assertFalse(rows.getFirst().has("stats.count"));
        assertEquals(3d, JsonUtils.getFromPath(rows.getFirst(), "stats.count").asJsonNumber().getValue().doubleValue());
    }
}
