package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReduceAfterStepTest {
    private static final String TAG = "tag";
    private static final List<String> IDS = List.of("m", "c", "x", "a", "q", "f", "z", "b", "k", "e", "t", "h");

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id, String tag) {
        final var obj = new JsonObject();
        if (id != null) {
            obj.add(Globals.PK_FIELD, new JsonString(id));
        }
        obj.addProperty(TAG, tag);
        return obj;
    }

    private static ReduceAggregationStep foldOf(String expression) {
        return new ReduceAggregationStep("export default (acc, row) => acc + '|' + " + expression + ";",
                new JsonString(""), "folded");
    }

    private static FilterAggregationStep taggedX() {
        return new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, TAG, new JsonString("x")));
    }

    private static String foldedOf(List<JsonObject> results) {
        assertEquals(1, results.size());
        return results.getFirst().get("folded").asJsonString().getValue();
    }

    private static String runOnStream(List<JsonObject> source, BaseAggregationStep... steps) throws IOException {
        return foldedOf(AggregationOperationHelper.processStepsOnStream(List.of(steps), source.stream()));
    }

    private static String runOnCollection(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return foldedOf(AggregationOperationHelper.processAggregation(request));
    }

    private static String sortedFold(List<String> ids) {
        return ids.stream().sorted().map(id -> "|" + id).collect(Collectors.joining());
    }

    private static List<JsonObject> shuffledSource() {
        final var source = new ArrayList<JsonObject>();
        IDS.forEach(id -> source.add(doc(id, "x")));
        return source;
    }

    private static void save(String id, String tag) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        request.setObject(doc(id, tag));
        IocContainer.get(OperationProcessor.class).processMessage(request);
        IocContainer.get(PendingIndexWrites.class).clear(TestGlobals.DB, TestGlobals.COLL, id, 0L);
    }

    private static void indexTag() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, TAG);
        IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(TAG));
    }

    @Test
    public void test_filter_then_reduce_folds_in_id_order_whatever_the_source_order() throws IOException {
        assertEquals(sortedFold(IDS), runOnStream(shuffledSource(), taggedX(), foldOf("row._id")));
    }

    @Test
    public void test_sort_then_filter_then_reduce_keeps_the_sort_order() throws IOException {
        final var descending = IDS.stream().sorted(Comparator.reverseOrder()).map(id -> "|" + id)
                .collect(Collectors.joining());
        assertEquals(descending, runOnStream(shuffledSource(), new SortAggregationStep(Globals.PK_FIELD, false),
                taggedX(), foldOf("row._id")));
    }

    @Test
    public void test_reduce_as_first_step_folds_its_stream_as_it_arrives() throws IOException {
        final var asArrived = IDS.stream().map(id -> "|" + id).collect(Collectors.joining());
        assertEquals(asArrived, runOnStream(shuffledSource(), foldOf("row._id")));
    }

    @Test
    public void test_rows_without_an_id_fold_after_identified_rows_by_canonical_text() throws IOException {
        final var source = List.of(doc(null, "beta"), doc("b", "x"), doc(null, "alpha"), doc("a", "x"));
        assertEquals("|a|b|alpha|beta",
                runOnStream(source, new SkipAggregationStep(0), foldOf("(row._id ?? row.tag)")));
    }

    @Test
    public void test_distinct_then_reduce_folds_in_canonical_order() throws IOException {
        final var source = List.of(doc("1", "pear"), doc("2", "apple"), doc("3", "fig"), doc("4", "apple"));
        assertEquals("|apple|fig|pear", runOnStream(source, new DistinctAggregationStep(TAG), foldOf("row.tag")));
    }

    @Test
    public void test_filter_then_reduce_agrees_indexed_and_scanned() throws Exception {
        for (var i = 0; i < IDS.size(); i++) {
            save(IDS.get(i), i % 3 == 0 ? "y" : "x");
        }
        final var matching = new ArrayList<String>();
        for (var i = 0; i < IDS.size(); i++) {
            if (i % 3 != 0) {
                matching.add(IDS.get(i));
            }
        }
        final var scanned = runOnCollection(taggedX(), foldOf("row._id"));
        indexTag();
        final var indexed = runOnCollection(taggedX(), foldOf("row._id"));
        final var forcedScan = runOnCollection(new SkipAggregationStep(0), taggedX(), foldOf("row._id"));

        assertEquals(sortedFold(matching), indexed);
        assertEquals(indexed, scanned);
        assertEquals(indexed, forcedScan);
    }

    @Test
    public void test_group_by_then_reduce_agrees_indexed_and_scanned() throws Exception {
        for (var i = 0; i < IDS.size(); i++) {
            save(IDS.get(i), "t" + (i % 5));
        }
        final var scanned = runOnCollection(new GroupByAggregationStep(TAG), foldOf("row.tag"));
        indexTag();
        final var indexed = runOnCollection(new GroupByAggregationStep(TAG), foldOf("row.tag"));

        assertEquals(Set.of("t0", "t1", "t2", "t3", "t4"), Set.of(indexed.substring(1).split("\\|")));
        assertEquals(indexed, scanned);
    }
}
