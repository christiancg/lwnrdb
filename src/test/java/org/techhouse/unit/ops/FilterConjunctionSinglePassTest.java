package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterConjunctionSinglePassTest {
    private static final String COLL = "singlePass";
    private static final int DOCUMENTS = 600;
    private static final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void seed() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLL));
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var doc = new JsonObject();
            doc.add("_id", new JsonString("d" + i));
            doc.add("n", new JsonNumber(i % 10));
            doc.add("m", new JsonNumber(i % 7));
            doc.add("k", new JsonNumber(i % 3));
            doc.add("location", new JsonGeo(i < 3 ? "#geo(40.0,-74.0)" : "#geo(34.05,-118.24)"));
            objects.add(doc);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.DB, COLL);
        bulk.setObjects(objects);
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, COLL, "location")).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, COLL, "k")).getStatus());
        IocContainer.get(PendingIndexWrites.class).clearCollection(TestGlobals.DB, COLL);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static FieldOperator field(FieldOperatorType type, String name, int value) {
        return new FieldOperator(type, name, new JsonNumber(value));
    }

    private static ConjunctionOperator conjunction(ConjunctionOperatorType type, BaseOperator... legs) {
        return new ConjunctionOperator(type, List.of(legs));
    }

    private static ConjunctionOperator unindexed(ConjunctionOperatorType type) {
        return conjunction(type, field(FieldOperatorType.GREATER_THAN_EQUALS, "n", 2),
                field(FieldOperatorType.NOT_EQUALS, "m", 3), field(FieldOperatorType.SMALLER_THAN, "n", 9));
    }

    private static AggregateAnalyzeResponse analyze(BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, COLL);
        request.setAnalyze(true);
        request.setAggregationSteps(List.of(steps));
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        return response;
    }

    private static long expectedCount(Predicate<Integer> matches) {
        var count = 0L;
        for (var i = 0; i < DOCUMENTS; i++) {
            count += matches.test(i) ? 1 : 0;
        }
        return count;
    }

    private static boolean andOf(int i) {
        return i % 10 >= 2 && i % 7 != 3 && i % 10 < 9;
    }

    @Test
    public void test_an_unindexed_and_reads_the_collection_once() {
        final var response = analyze(new FilterAggregationStep(unindexed(ConjunctionOperatorType.AND)));

        assertEquals(expectedCount(FilterConjunctionSinglePassTest::andOf), response.getResults().size());
        assertEquals(DOCUMENTS, response.getAnalyzeResult().getDocumentsScanned());
    }

    @Test
    public void test_an_unindexed_and_under_a_limit_stops_early() {
        final var response = analyze(new FilterAggregationStep(unindexed(ConjunctionOperatorType.AND)),
                new LimitAggregationStep(10));

        assertEquals(10, response.getResults().size());
        assertTrue(response.getAnalyzeResult().getDocumentsScanned() < DOCUMENTS);
    }

    @Test
    public void test_nor_and_nand_read_the_collection_once_and_answer_exactly() {
        final var nor = analyze(new FilterAggregationStep(unindexed(ConjunctionOperatorType.NOR)));
        final var nand = analyze(new FilterAggregationStep(unindexed(ConjunctionOperatorType.NAND)));

        assertEquals(expectedCount(i -> i % 10 < 2 && i % 7 == 3 && i % 10 >= 9), nor.getResults().size());
        assertEquals(expectedCount(i -> !andOf(i)), nand.getResults().size());
        assertEquals(DOCUMENTS, nor.getAnalyzeResult().getDocumentsScanned());
        assertEquals(DOCUMENTS, nand.getAnalyzeResult().getDocumentsScanned());
    }

    @Test
    public void test_a_script_leg_runs_once_per_document_whatever_the_other_legs_decide() {
        final var script = new ScriptOperator("export default (doc) => doc.n > 4;");
        final var operator = conjunction(ConjunctionOperatorType.AND, field(FieldOperatorType.EQUALS, "n", 1), script);

        final var response = analyze(new FilterAggregationStep(operator));

        assertEquals(0, response.getResults().size());
        assertEquals(DOCUMENTS, response.getAnalyzeResult().getScriptInvocations());
    }

    @Test
    public void test_an_or_of_a_geo_leg_and_an_indexed_leg_uses_the_indexes() {
        final var polygon = new JsonArray();
        polygon.add(new JsonGeo("#geo(39.9,-74.1)"));
        polygon.add(new JsonGeo("#geo(39.9,-73.9)"));
        polygon.add(new JsonGeo("#geo(40.1,-73.9)"));
        polygon.add(new JsonGeo("#geo(40.1,-74.1)"));
        final var args = new JsonObject();
        args.add("polygon", polygon);
        final var within = new CustomOperator("within", "location", null, args);
        final var operator = conjunction(ConjunctionOperatorType.OR, within, field(FieldOperatorType.EQUALS, "k", 1));

        final var response = analyze(new FilterAggregationStep(operator));

        assertEquals(expectedCount(i -> i < 3 || i % 3 == 1), response.getResults().size());
        assertTrue(response.getAnalyzeResult().isIndexUsed());
        assertTrue(response.getAnalyzeResult().getIndexesUsed().contains("location"));
        assertTrue(response.getAnalyzeResult().getDocumentsScanned() < DOCUMENTS);
    }
}
