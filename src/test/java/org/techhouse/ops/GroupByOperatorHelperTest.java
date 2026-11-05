package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.GroupByOperatorHelper.GroupDemand;
import org.techhouse.ops.index.IndexEntryReader;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class GroupByOperatorHelperTest {
    private static final String INDEXED = "groupDemand";
    private static final String EDGES = "groupEdges";
    private static final int DOCUMENTS = 240;
    private static final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private static final PendingIndexWrites pending = IocContainer.get(PendingIndexWrites.class);

    @BeforeAll
    static void seed() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final List<JsonBaseElement> kinds = List.of(new JsonString("A"), new JsonString("B"), new JsonString("a"),
                new JsonNumber(1), new JsonString("1"), JsonBoolean.TRUE);
        final var indexed = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            indexed.add(document("d" + (1000 - i), kinds.get(i % kinds.size())));
        }
        save(INDEXED, indexed);
        final var edges = new ArrayList<JsonObject>();
        for (var i = 0; i < 60; i++) {
            final var value = switch (i % 5) {
                case 0 -> JsonNull.INSTANCE;
                case 1 -> null;
                case 2 -> new JsonString("A");
                case 3 -> new JsonNumber(i % 2 == 0 ? 0.0 : -0.0);
                default -> new JsonNumber(2);
            };
            edges.add(document("e" + (500 - i), value));
        }
        save(EDGES, edges);
    }

    private static JsonObject document(String id, JsonBaseElement cat) {
        final var doc = new JsonObject();
        doc.add("_id", new JsonString(id));
        if (cat != null) {
            doc.add("cat", cat);
        }
        doc.add("n", new JsonNumber(id.length()));
        return doc;
    }

    private static void save(String coll, List<JsonObject> objects) {
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        final var bulk = new BulkSaveRequest(TestGlobals.DB, coll);
        bulk.setObjects(objects);
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "cat")).getStatus());
        pending.clearCollection(TestGlobals.DB, coll);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static List<JsonObject> run(String coll, BaseAggregationStep... steps) throws Exception {
        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request);
    }

    private static int countOf(List<JsonObject> rows) {
        assertEquals(1, rows.size());
        return rows.getFirst().get("count").asJsonNumber().asInteger();
    }

    private static AddFieldMapOperator sizeOf(String target, String operand) {
        return new AddFieldMapOperator(target, null, new OneParamMidOperator(MidOperationType.SIZE, operand));
    }

    private static RemoveFieldMapOperator remove(String field) {
        return new RemoveFieldMapOperator(field, null);
    }

    private static MapAggregationStep map(MapOperator... operators) {
        return new MapAggregationStep(List.of(operators));
    }

    private static FieldOperator condition() {
        return new FieldOperator(FieldOperatorType.EQUALS, "group.0._id", new JsonString("x"));
    }

    private static List<String> groupIds(List<JsonObject> rows) {
        final var ids = new ArrayList<String>();
        rows.getFirst().get("group").asJsonArray()
                .forEach(document -> ids.add(document.asJsonObject().get("_id").asJsonString().getValue()));
        return ids;
    }

    private static List<JsonObject> groupedStream(GroupDemand demand) throws Exception {
        try (var context = PipelineContext.of(0L, Long.MAX_VALUE, null)) {
            final var rows = Stream.of("c", "a", "b").map(id -> document(id, new JsonString("k")));
            return GroupByOperatorHelper
                    .processGroupByStep(new GroupByAggregationStep("cat"), rows, "", "", demand, context).toList();
        }
    }

    @Test
    public void test_a_count_next_asks_only_for_the_keys() {
        assertEquals(GroupDemand.KEYS, GroupByOperatorHelper.demandOf(new CountAggregationStep()));
    }

    @Test
    public void test_a_size_then_remove_map_skips_the_id_order() throws Exception {
        assertEquals(GroupDemand.SIZES, GroupByOperatorHelper.demandOf(map(sizeOf("size", "group"), remove("group"))));
        assertEquals(GroupDemand.SIZES, GroupByOperatorHelper
                .demandOf(map(sizeOf("size", "group"), sizeOf("stats.size", "group"), remove("group"))));
        assertEquals(List.of("c", "a", "b"), groupIds(groupedStream(GroupDemand.SIZES)));
        assertEquals(List.of("a", "b", "c"), groupIds(groupedStream(GroupDemand.ORDERED_GROUPS)));
    }

    @Test
    public void test_a_map_that_keeps_the_group_still_sorts_it() {
        final List<BaseAggregationStep> nearMisses = new ArrayList<>(List.of(map(sizeOf("size", "group")),
                map(remove("group"), sizeOf("size", "group")), map(sizeOf("first", "group.0"), remove("group")),
                map(new AddFieldMapOperator("abs", null, new OneParamMidOperator(MidOperationType.ABS, "group")),
                        remove("group")),
                map(sizeOf("size", "group"), remove("other")),
                map(sizeOf("size", "group"), new RemoveFieldMapOperator("group", condition())),
                map(new AddFieldMapOperator("size", condition(),
                        new OneParamMidOperator(MidOperationType.SIZE, "group")), remove("group")),
                map(sizeOf("group", "group"), remove("group")), map(sizeOf("group.size", "group"), remove("group")),
                map(new AddFieldMapOperator(null, null, new OneParamMidOperator(MidOperationType.SIZE, "group")),
                        remove("group")),
                new MapAggregationStep(List.of()), new MapAggregationStep(null), new SortAggregationStep("cat", true),
                new SkipAggregationStep(1)));
        nearMisses.add(null);
        for (final var next : nearMisses) {
            assertEquals(GroupDemand.ORDERED_GROUPS, GroupByOperatorHelper.demandOf(next), String.valueOf(next));
        }
    }

    @Test
    public void test_a_sized_and_removed_group_answers_exactly_like_the_sorted_one() throws Exception {
        for (final var coll : List.of(INDEXED, EDGES)) {
            final var skipped = run(coll, new GroupByAggregationStep("cat"),
                    map(sizeOf("size", "group"), remove("group")));
            final var sorted = run(coll, new GroupByAggregationStep("cat"), map(sizeOf("size", "group")),
                    map(remove("group")));
            assertEquals(sorted.toString(), skipped.toString());
            final var scanned = run(coll, new SkipAggregationStep(0), new GroupByAggregationStep("cat"),
                    map(sizeOf("size", "group"), remove("group")));
            assertEquals(run(coll, new SkipAggregationStep(0), new GroupByAggregationStep("cat"),
                    map(sizeOf("size", "group")), map(remove("group"))).toString(), scanned.toString());
        }
    }

    @Test
    public void test_an_indexed_group_count_copies_no_id_set() throws Exception {
        try (var reader = mockStatic(IndexEntryReader.class, CALLS_REAL_METHODS)) {
            final var counted = countOf(run(INDEXED, new GroupByAggregationStep("cat"), new CountAggregationStep()));
            assertEquals(6, counted);
            reader.verify(() -> IndexEntryReader.getIndexEntriesForField(any(), any(), any(), any()), never());
            reader.verify(
                    () -> IndexEntryReader.distinctValuesForField(eq(TestGlobals.DB), eq(INDEXED), eq("cat"), any()));
        }
        assertEquals(6, run(INDEXED, new GroupByAggregationStep("cat")).size());
        assertEquals(6, countOf(run(INDEXED, new SkipAggregationStep(0), new GroupByAggregationStep("cat"),
                new CountAggregationStep())));
        pending.mark(TestGlobals.DB, INDEXED, "d1000");
        try {
            assertEquals(6, countOf(run(INDEXED, new GroupByAggregationStep("cat"), new CountAggregationStep())));
        } finally {
            pending.clearCollection(TestGlobals.DB, INDEXED);
        }
    }

    @Test
    public void test_a_group_count_with_null_and_missing_keys_counts_what_the_grouping_emits() throws Exception {
        final var groups = run(EDGES, new GroupByAggregationStep("cat"));
        assertEquals(4, groups.size());
        assertTrue(groups.stream().anyMatch(row -> row.get("cat").isJsonNull()));
        assertEquals(groups.size(), countOf(run(EDGES, new GroupByAggregationStep("cat"), new CountAggregationStep())));
        assertEquals(groups.size(), countOf(
                run(EDGES, new SkipAggregationStep(0), new GroupByAggregationStep("cat"), new CountAggregationStep())));
        final var sized = run(EDGES, new GroupByAggregationStep("cat"), map(sizeOf("size", "group"), remove("group")));
        assertEquals(48, sized.stream().mapToInt(row -> row.get("size").asJsonNumber().asInteger()).sum());
    }
}
