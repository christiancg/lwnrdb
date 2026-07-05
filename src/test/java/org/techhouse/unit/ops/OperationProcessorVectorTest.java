package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

// End-to-end coverage of the vector "nearest" (top-K) ranking operator through the full SAVE / AGGREGATE
// pipeline, with and without a field index (which drives the SimHash pre-filter in
// VectorSimilarityIndexHelper).
public class OperationProcessorVectorTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    // nearest over a scanned (un-indexed) collection returns the K most similar, ordered by similarity.
    @Test
    public void test_nearest_without_index_returns_topk_ordered() {
        final var coll = "vecScan";
        seed(coll);

        final var response = aggregate(coll, nearestStep(2, false));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(List.of("best", "second"), orderedIds(response.getResults()));
    }

    // The same query over an indexed collection returns the same top-K and reports the index as used.
    @Test
    public void test_nearest_with_index_reports_index_used() {
        final var coll = "vecIdx";
        seed(coll);
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "embedding")).getStatus());

        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAnalyze(true);
        request.setAggregationSteps(List.of(nearestStep(2, false)));
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);

        assertEquals(List.of("best", "second"), orderedIds(response.getResults()));
        assertTrue(response.getAnalyzeResult().isIndexUsed());
        assertTrue(response.getAnalyzeResult().getIndexesUsed().contains("embedding"));
    }

    // exact:true forces a full scan (guaranteed-exact top-K) and does not use the index.
    @Test
    public void test_nearest_exact_true_no_index_used() {
        final var coll = "vecExact";
        seed(coll);
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "embedding"));

        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAnalyze(true);
        request.setAggregationSteps(List.of(nearestStep(2, true)));
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);

        assertEquals(List.of("best", "second"), orderedIds(response.getResults()));
        assertFalse(response.getAnalyzeResult().isIndexUsed());
    }

    // A K larger than the collection returns every document, still ordered by similarity.
    @Test
    public void test_nearest_k_larger_than_collection_returns_all_ordered() {
        final var coll = "vecAll";
        seed(coll);

        final var response = aggregate(coll, nearestStep(100, false));

        assertEquals(List.of("best", "second", "third", "worst"), orderedIds(response.getResults()));
    }

    // Documents whose field is absent, not a vector, or a mismatched-dimension vector (undefined
    // similarity) simply do not rank.
    @Test
    public void test_nearest_ignores_missing_or_non_vector_field() {
        final var coll = "vecMixed";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        saveVector(coll, "best", "#vector(10.0,0.0)");
        saveVector(coll, "second", "#vector(9.0,1.0)");
        saveNoField();
        saveScalarField();
        saveVector(coll, "wrongdim", "#vector(1.0,0.0,0.0)");

        final var response = aggregate(coll, nearestStep(10, false));

        assertEquals(List.of("best", "second"), orderedIds(response.getResults()));
    }

    // COUNT after a nearest filter falls back to the document-reading count and returns min(k, matches).
    @Test
    public void test_count_after_nearest() {
        final var coll = "vecCount";
        seed(coll);
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "embedding"));

        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAggregationSteps(List.of(nearestStep(2, false), new CountAggregationStep()));
        final var response = (AggregateResponse) processor.processMessage(request);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(2, response.getResults().getFirst().get("count").asJsonNumber().getValue().intValue());
    }

    // Four vectors with strictly decreasing cosine similarity to (10,0): best > second > third > worst.
    private void seed(String coll) {
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        saveVector(coll, "best", "#vector(10.0,0.0)");
        saveVector(coll, "second", "#vector(9.0,1.0)");
        saveVector(coll, "third", "#vector(1.0,1.0)");
        saveVector(coll, "worst", "#vector(0.0,10.0)");
    }

    private void saveVector(String coll, String id, String vector) {
        final var save = new SaveRequest(TestGlobals.DB, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        obj.add("embedding", new JsonVector(vector));
        save.setObject(obj);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
    }

    private void saveNoField() {
        final var save = new SaveRequest(TestGlobals.DB, "vecMixed");
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("nofield"));
        obj.add("name", new JsonString("nofield"));
        save.setObject(obj);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
    }

    private void saveScalarField() {
        final var save = new SaveRequest(TestGlobals.DB, "vecMixed");
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("notvector"));
        obj.add("embedding", new JsonNumber(42));
        save.setObject(obj);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
    }

    private AggregateResponse aggregate(String coll, FilterAggregationStep step) {
        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAggregationSteps(List.of(step));
        return (AggregateResponse) processor.processMessage(request);
    }

    private static FilterAggregationStep nearestStep(int k, boolean exact) {
        final var q = new JsonVector("#vector(10.0,0.0)");
        final var args = new JsonObject();
        args.add("value", q);
        args.add("k", new JsonNumber((double) k));
        if (exact) {
            args.add("exact", new JsonBoolean(true));
        }
        return new FilterAggregationStep(new CustomOperator("nearest", "embedding", q, args));
    }

    private static List<String> orderedIds(List<JsonObject> results) {
        return results.stream().map(o -> o.get("_id").asJsonString().getValue()).toList();
    }
}
