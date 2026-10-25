package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonArray;
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
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AnalyzeGoldenTest {
    private static final String COLL = "analyzeGolden";
    private static final String JOINED = "analyzeGoldenJoined";
    private static final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLL));
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, JOINED));
        for (var i = 0; i < 6; i++) {
            final var document = new JsonObject();
            document.add("_id", new JsonString("d" + i));
            document.add("n", new JsonNumber(i % 3));
            final var meta = new JsonObject();
            meta.add("k", new JsonNumber(i % 2));
            document.add("meta", meta);
            document.add("location", new JsonGeo(i < 3 ? "#geo(40.0,-74.0)" : "#geo(34.05,-118.24)"));
            document.add("embedding", new JsonVector("#vector(" + (10.0 - i) + "," + (double) i + ")"));
            save(COLL, document);
            final var joined = new JsonObject();
            joined.add("_id", new JsonString("j" + i));
            joined.add("ref", new JsonNumber(i % 3));
            save(JOINED, joined);
        }
        for (final var field : List.of("n", "meta", "location", "embedding")) {
            processor.processMessage(new CreateIndexRequest(TestGlobals.DB, COLL, field));
        }
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, JOINED, "ref"));
        final var pending = new JsonObject();
        pending.add("_id", new JsonString("d6"));
        pending.add("n", new JsonNumber(1));
        save(COLL, pending);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void save(String coll, JsonObject document) {
        final var request = new SaveRequest(TestGlobals.DB, coll);
        request.setObject(document);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private static String analyzed(BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, COLL);
        request.setAnalyze(true);
        request.setAggregationSteps(List.of(steps));
        final var result = ((AggregateAnalyzeResponse) processor.processMessage(request)).getAnalyzeResult();
        return "scanned=" + result.getDocumentsScanned() + " indexes="
                + result.getIndexesUsed().stream().sorted().toList() + " locks="
                + result.getLocksAcquired().stream().sorted().toList();
    }

    private static FilterAggregationStep field(FieldOperatorType type, String field,
            org.techhouse.ejson.elements.JsonBaseElement value) {
        return new FilterAggregationStep(new FieldOperator(type, field, value));
    }

    @Test
    public void aScalarIndexFilter() {
        assertEquals("scanned=10 indexes=[n] locks=[myDb|analyzeGolden, myDb|analyzeGolden|n]",
                analyzed(field(FieldOperatorType.EQUALS, "n", new JsonNumber(1))));
    }

    @Test
    public void aHashIndexFilter() {
        final var operand = new JsonObject();
        operand.add("k", new JsonNumber(1));
        assertEquals("scanned=10 indexes=[meta] locks=[myDb|analyzeGolden, myDb|analyzeGolden|meta]",
                analyzed(field(FieldOperatorType.EQUALS, "meta", operand)));
    }

    @Test
    public void aGeoIndexFilter() {
        final var args = new JsonObject();
        final var target = new JsonGeo("#geo(40.0,-74.0)");
        args.add("value", target);
        args.add("comparator", new JsonString("SMALLER_THAN"));
        args.add("distance", new JsonNumber(1000.0));
        assertEquals("scanned=7 indexes=[location] locks=[myDb|analyzeGolden, myDb|analyzeGolden|location]",
                analyzed(new FilterAggregationStep(new CustomOperator("distance", "location", target, args))));
    }

    @Test
    public void aVectorIndexRanking() {
        final var args = new JsonObject();
        final var query = new JsonVector("#vector(10.0,0.0)");
        args.add("value", query);
        args.add("k", new JsonNumber(2.0));
        assertEquals("scanned=7 indexes=[embedding] locks=[myDb|analyzeGolden, myDb|analyzeGolden|embedding]",
                analyzed(new FilterAggregationStep(new CustomOperator("nearest", "embedding", query, args))));
    }

    @Test
    public void aPrimaryKeyFilter() {
        final var ids = new JsonArray();
        ids.add(new JsonString("d1"));
        ids.add(new JsonString("d4"));
        assertEquals("scanned=2 indexes=[_id] locks=[myDb|analyzeGolden, myDb|analyzeGolden]",
                analyzed(field(FieldOperatorType.IN, "_id", ids)));
    }

    @Test
    public void anIndexedJoin() {
        assertEquals(
                "scanned=19 indexes=[ref] locks=[myDb|analyzeGolden, myDb|analyzeGoldenJoined, "
                        + "myDb|analyzeGoldenJoined|ref]",
                analyzed(new JoinAggregationStep(JOINED, "n", "ref", "joined")));
    }

    @Test
    public void anIndexedSort() {
        assertEquals("scanned=9 indexes=[n] locks=[myDb|analyzeGolden, myDb|analyzeGolden|n]",
                analyzed(new SortAggregationStep("n", true), new LimitAggregationStep(2)));
    }

    @Test
    public void anIndexedGroupBy() {
        assertEquals("scanned=14 indexes=[n] locks=[myDb|analyzeGolden, myDb|analyzeGolden|n]",
                analyzed(new GroupByAggregationStep("n")));
    }

    @Test
    public void anIndexFilterWithALimit() {
        assertEquals("scanned=8 indexes=[n] locks=[myDb|analyzeGolden, myDb|analyzeGolden|n]",
                analyzed(field(FieldOperatorType.EQUALS, "n", new JsonNumber(1)), new LimitAggregationStep(1)));
    }

    @Test
    public void aFullScanFilter() {
        assertEquals("scanned=7 indexes=[] locks=[myDb|analyzeGolden]",
                analyzed(field(FieldOperatorType.EQUALS, "missing", new JsonNumber(1))));
    }
}
