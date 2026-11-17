package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.heap.DocumentHeapSizer;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.GroupByOperatorHelper.GroupDemand;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;

public class HeldOverheadChargingTest {
    private static final int ROWS = 200;
    private static final int GROUPS = 4;
    private final AggregateHeldBudget pool = IocContainer.get(AggregateHeldBudget.class);

    private static List<JsonObject> rows(int count, int padding) {
        final var rows = new ArrayList<JsonObject>();
        for (var i = 0; i < count; i++) {
            final var row = new JsonObject();
            row.add("_id", new JsonString("r" + i));
            row.add("unique", new JsonNumber(i));
            row.add("shared", new JsonNumber(i % GROUPS));
            row.add("pad", new JsonString("p".repeat(padding)));
            rows.add(row);
        }
        return rows;
    }

    private long chargedByGrouping(String fieldName) throws Exception {
        final var before = pool.reservedBytes();
        try (var context = PipelineContext.of(0L, Long.MAX_VALUE, null)) {
            GroupByOperatorHelper.processGroupByStep(new GroupByAggregationStep(fieldName), rows(ROWS, 20).stream(),
                    TestGlobals.DB, TestGlobals.COLL, GroupDemand.ORDERED_GROUPS, context).forEach(_ -> {
                    });
            return pool.reservedBytes() - before;
        }
    }

    @Test
    public void eachNewGroupIsChargedItsOverheadOnce() throws Exception {
        final var fewGroups = chargedByGrouping("shared");
        final var oneGroupPerRow = chargedByGrouping("unique");

        assertEquals((ROWS - GROUPS) * GroupOverhead.BYTES, oneGroupPerRow - fewGroups);
    }

    @Test
    public void theGroupOverheadCoversItsContainers() {
        final var layout = HeapLayout.current();
        final var key = new JsonObject();
        key.add("unique", new JsonNumber(1));

        assertTrue(GroupOverhead.BYTES > DocumentHeapSizer.sizeOf(key, layout),
                "a group's map node, key, list and output row outweigh a one-field row");
        assertEquals(GroupOverhead.BYTES, GroupOverhead.bytesPerGroup(layout));
    }

    @Test
    public void aBoundedSortOverLargeRowsAnswers400_36() {
        try (var context = PipelineContext.of(0L, 64 * 1024L, null)) {
            final var refused = assertThrows(AggregateLimitException.class,
                    () -> SortOperatorHelper.processSortStep(new SortAggregationStep("unique", true),
                            rows(400, 1024).stream(), TestGlobals.DB, TestGlobals.COLL, 300, 0, context).rows()
                            .forEach(_ -> {
                            }));
            assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE, refused.getErrorCode());
        }
    }

    @Test
    public void rowsTheBoundedSortEvictsAreReleased() throws Exception {
        final var bound = 10;
        final var rows = rows(1_000, 20);
        final var largest = rows.stream().mapToLong(HeldRows::bytesAtMost).max().orElseThrow();
        final var before = pool.reservedBytes();
        try (var context = PipelineContext.of(0L, Long.MAX_VALUE, null)) {
            final List<JsonObject> sorted = SortOperatorHelper.processSortStep(new SortAggregationStep("unique", false),
                    rows.stream(), TestGlobals.DB, TestGlobals.COLL, bound, 0, context).rows().toList();

            assertEquals(bound, sorted.size());
            assertEquals(999.0, sorted.getFirst().get("unique").asJsonNumber().getValue());
            assertTrue(pool.reservedBytes() - before <= bound * largest,
                    "only the rows the heap still holds stay charged: " + (pool.reservedBytes() - before));
        }
        assertEquals(before, pool.reservedBytes());
    }

    @Test
    public void aSmallBoundedSortIsAnswered() throws Exception {
        try (var context = PipelineContext.of(0L, 64 * 1024L, null)) {
            final var sorted = SortOperatorHelper.processSortStep(new SortAggregationStep("unique", true),
                    Stream.of(rows(5, 10).toArray(JsonObject[]::new)), TestGlobals.DB, TestGlobals.COLL, 3, 0, context)
                    .rows().toList();

            assertEquals(3, sorted.size());
        }
    }
}
