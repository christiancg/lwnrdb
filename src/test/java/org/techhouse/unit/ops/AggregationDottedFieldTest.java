package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.JsonUtils;

public class AggregationDottedFieldTest extends FilterResolutionSupport {
    @BeforeEach
    public void seedNested() throws IOException, InterruptedException {
        TestUtils.createTestJoinCollection();
        insertNested(TestGlobals.COLL, "1", "x");
        insertNested(TestGlobals.COLL, "2", "y");
        insertNested(TestGlobals.COLL, "3", "x");
    }

    private void insertNested(String collName, String id, String nestedValue) throws IOException {
        final var inner = new JsonObject();
        inner.add("b", new JsonString(nestedValue));
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("a", inner);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, collName, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, collName, entry);
    }

    private static FilterAggregationStep equalsX() {
        return filter(FieldOperatorType.EQUALS, "a.b", new JsonString("x"));
    }

    @Test
    public void test_group_by_a_dotted_field_emits_nested_on_scan_and_index() throws Exception {
        final var scanned = rows(new GroupByAggregationStep("a.b"));
        indexField("a.b");
        final var indexed = rows(new GroupByAggregationStep("a.b"));

        assertEquals(2, scanned.size());
        for (final var row : scanned) {
            assertFalse(row.has("a.b"));
            assertTrue(JsonUtils.getFromPath(row, "a.b").isJsonString());
        }
        assertEquals(sortedJson(scanned), sortedJson(indexed));
    }

    @Test
    public void test_distinct_a_dotted_field_emits_nested_on_scan_and_index() throws Exception {
        final var scanned = rows(new DistinctAggregationStep("a.b"));
        indexField("a.b");
        final var indexed = rows(new DistinctAggregationStep("a.b"));

        assertEquals(List.of("{\"a\":{\"b\":\"x\"}}", "{\"a\":{\"b\":\"y\"}}"), sortedJson(scanned));
        assertEquals(sortedJson(scanned), sortedJson(indexed));
    }

    @Test
    public void test_group_by_then_filter_on_the_same_dotted_field() throws Exception {
        final var rows = rows(new GroupByAggregationStep("a.b"), equalsX());

        assertEquals(1, rows.size());
        assertEquals(2, rows.getFirst().get("group").asJsonArray().size());
    }

    @Test
    public void test_distinct_then_sort_on_a_dotted_field_orders_rows() throws Exception {
        final var rows = rows(new DistinctAggregationStep("a.b"), new SortAggregationStep("a.b", false));

        assertEquals("y", JsonUtils.getFromPath(rows.get(0), "a.b").asJsonString().getValue());
        assertEquals("x", JsonUtils.getFromPath(rows.get(1), "a.b").asJsonString().getValue());
    }

    @Test
    public void test_join_as_a_dotted_field_nests_the_array() throws Exception {
        insertNested(TestGlobals.JOIN_COLL, "r1", "x");
        final var rows = rows(equalsX(), new JoinAggregationStep(TestGlobals.JOIN_COLL, "a.b", "a.b", "joined.rows"));

        assertEquals(2, rows.size());
        for (final var row : rows) {
            assertFalse(row.has("joined.rows"));
            assertEquals(1, JsonUtils.getFromPath(row, "joined.rows").asJsonArray().size());
        }
    }

    @Test
    public void test_reduce_with_a_dotted_result_field_nests_the_value() throws Exception {
        final var rows = rows(
                new ReduceAggregationStep("export default (acc, doc) => acc + 1;", new JsonNumber(0), "stats.count"));

        assertEquals(1, rows.size());
        assertFalse(rows.getFirst().has("stats.count"));
        assertEquals(3d, JsonUtils.getFromPath(rows.getFirst(), "stats.count").asJsonNumber().getValue().doubleValue());
    }
}
