package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ComplementIndexAgreementTest {
    private static final String COLL = "complements";
    private static final int DOCUMENTS = 400;
    private static final List<String> FIELDS = List.of("pure", "holes", "mixed", "flag", "word");
    private static final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static Long savedMaxMemory;

    @BeforeAll
    static void seed() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLL));
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var doc = new JsonObject();
            doc.add("_id", new JsonString("c" + i));
            doc.add("pure", new JsonNumber(i % 10));
            if (i % 7 == 0) {
                doc.add("holes", JsonNull.INSTANCE);
            } else if (i % 11 != 0) {
                doc.add("holes", new JsonNumber(i % 5));
            }
            doc.add("mixed", i % 3 == 0 ? new JsonString("s" + i % 4) : new JsonNumber(i % 4));
            doc.add("flag", JsonBoolean.of(i % 4 == 0));
            doc.add("word", new JsonString((i % 2 == 0 ? "Word" : "word") + i % 6));
            objects.add(doc);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.DB, COLL);
        bulk.setObjects(objects);
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());
        for (final var field : FIELDS) {
            assertEquals(OperationStatus.OK,
                    processor.processMessage(new CreateIndexRequest(TestGlobals.DB, COLL, field)).getStatus());
        }
        IocContainer.get(PendingIndexWrites.class).clearCollection(TestGlobals.DB, COLL);
    }

    @AfterEach
    void restore() throws Exception {
        TestUtils.setPrivateField(configuration, "indexScanFallbackPercent",
                ConfigKey.INDEX_SCAN_FALLBACK_PERCENT.defaultValue());
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AggregateAnalyzeResponse run(List<BaseAggregationStep> steps) {
        final var request = new AggregateRequest(TestGlobals.DB, COLL);
        request.setAnalyze(true);
        request.setAggregationSteps(steps);
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        return response;
    }

    private static List<String> ids(AggregateAnalyzeResponse response) {
        return response.getResults().stream()
                .map(row -> row.has("_id")
                        ? row.get("_id").asJsonString().getValue()
                        : "count=" + row.get("count").asJsonNumber().doubleValue())
                .sorted().toList();
    }

    private static List<FieldOperator> operators() {
        final var operators = new ArrayList<FieldOperator>();
        final List<JsonBaseElement> scalars = List.of(new JsonNumber(1), new JsonNumber(3.0), new JsonString("s1"),
                new JsonString("WORD2"), JsonBoolean.of(true), JsonNull.INSTANCE);
        for (final var field : FIELDS) {
            for (final var scalar : scalars) {
                operators.add(new FieldOperator(FieldOperatorType.NOT_EQUALS, field, scalar));
                operators.add(new FieldOperator(FieldOperatorType.EQUALS, field, scalar));
                final var list = new JsonArray();
                list.add(scalar);
                operators.add(new FieldOperator(FieldOperatorType.NOT_IN, field, list));
                operators.add(new FieldOperator(FieldOperatorType.IN, field, list));
            }
            final var many = new JsonArray();
            for (var i = 0; i < 9; i++) {
                many.add(new JsonNumber(i));
            }
            operators.add(new FieldOperator(FieldOperatorType.NOT_IN, field, many));
            operators.add(new FieldOperator(FieldOperatorType.GREATER_THAN_EQUALS, field, new JsonNumber(2)));
            operators.add(new FieldOperator(FieldOperatorType.SMALLER_THAN, field, new JsonNumber(2)));
        }
        return operators;
    }

    private static void assertIndexMatchesScan(String label) {
        for (final var operator : operators()) {
            final var scanned = ids(run(List.of(new SkipAggregationStep(0), new FilterAggregationStep(operator))));
            final var indexed = ids(run(List.of(new FilterAggregationStep(operator))));
            assertEquals(scanned, indexed, label + " " + operator.getFieldOperatorType() + " " + operator.getField()
                    + " " + operator.getValue());
            final var counted = ids(run(List.of(new FilterAggregationStep(operator), new CountAggregationStep())));
            assertEquals(List.of("count=" + (double) scanned.size()), counted, label + " COUNT " + operator.getField());
        }
    }

    @Test
    public void answersMatchScan() throws Exception {
        TestUtils.setPrivateField(configuration, "indexScanFallbackPercent", 100);
        assertIndexMatchesScan("resident");
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_DISABLED);
        cache.evictCollection(TestGlobals.DB, COLL);
        assertIndexMatchesScan("from the index file");
        TestUtils.setPrivateField(configuration, "indexScanFallbackPercent",
                ConfigKey.INDEX_SCAN_FALLBACK_PERCENT.defaultValue());
        assertIndexMatchesScan("gated");
    }

    @Test
    public void aSelectiveComplementIsServedFromTheIndexFile() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_DISABLED);
        cache.evictCollection(TestGlobals.DB, COLL);
        final var many = new JsonArray();
        for (var i = 0; i < 9; i++) {
            many.add(new JsonNumber(i));
        }
        final var response = run(
                List.of(new FilterAggregationStep(new FieldOperator(FieldOperatorType.NOT_IN, "pure", many))));
        assertEquals(DOCUMENTS / 10, response.getResults().size());
        assertTrue(response.getAnalyzeResult().getIndexesUsed().contains("pure"));
        assertEquals(DOCUMENTS / 10, response.getAnalyzeResult().getDocumentsScanned());
        final var equals = run(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "word", new JsonString("WORD3")))));
        assertEquals(DOCUMENTS / 6 + 1, equals.getResults().size());
        assertEquals(equals.getResults().size(), equals.getAnalyzeResult().getDocumentsScanned());
    }
}
