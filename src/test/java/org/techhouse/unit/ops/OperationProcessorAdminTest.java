package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ReindexResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorAdminTest {
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

    // An index-backed filter reports the index as used.
    @Test
    public void test_aggregation_with_analyze_reports_index_used() {
        final var coll = "analyzeIndexColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        final var saveRequest = new SaveRequest(TestGlobals.DB, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("status", new JsonString("active"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);
        // Index creation is synchronous, so the index is available to the next aggregation.
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "status"));

        final var aggregateRequest = new AggregateRequest(TestGlobals.DB, coll);
        aggregateRequest.setAnalyze(true);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")))));

        final var response = (AggregateAnalyzeResponse) processor.processMessage(aggregateRequest);

        final var analyzeResult = response.getAnalyzeResult();
        assertTrue(analyzeResult.isIndexUsed());
        assertTrue(analyzeResult.getIndexesUsed().contains("status"));
        assertTrue(analyzeResult.getLocksAcquired()
                .contains(Cache.getCollectionIdentifier(TestGlobals.DB, coll) + "|status"));
    }

    // No index on the filtered field → suggestion recommends creating one.
    @Test
    public void test_aggregation_with_analyze_no_index_suggests_creation() {
        final var coll = "analyzeNoIndexColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        final var saveRequest = new SaveRequest(TestGlobals.DB, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("notIndexed", new JsonString("v"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        final var aggregateRequest = new AggregateRequest(TestGlobals.DB, coll);
        aggregateRequest.setAnalyze(true);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "notIndexed", new JsonString("v")))));

        final var response = (AggregateAnalyzeResponse) processor.processMessage(aggregateRequest);

        final var analyzeResult = response.getAnalyzeResult();
        assertFalse(analyzeResult.isIndexUsed());
        assertTrue(analyzeResult.getSuggestions().stream()
                .anyMatch(s -> s.startsWith("No index was used") && s.contains("notIndexed")));
    }

    @Test
    public void test_find_by_id_records_pk_index_access() {
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("usage-id-2"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);
        final var mm = IocContainer.get(org.techhouse.cache.MemoryManagement.class);
        final var before = mm.getCounter(org.techhouse.cache.AccessKind.PK_INDEX, TestGlobals.DB, TestGlobals.COLL,
                null);
        final var beforeCount = before == null ? 0L : before.getAccessCount();
        FindByIdRequest request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("usage-id-2");
        processor.processMessage(request);
        final var after = mm.getCounter(org.techhouse.cache.AccessKind.PK_INDEX, TestGlobals.DB, TestGlobals.COLL,
                null);
        assertNotNull(after);
        assertTrue(after.getAccessCount() > beforeCount);
    }

    // REINDEX: rebuild all registered indexes when no fieldNames given
    @Test
    public void test_reindex_all_fields_rebuilds_registered_indexes() {
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "reindexField"));
        try {
            ReindexRequest request = new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, null);
            ReindexResponse response = (ReindexResponse) processor.processMessage(request);
            assertEquals(OperationStatus.OK, response.getStatus());
            assertTrue(response.getRebuiltFields().contains("reindexField"));
        } finally {
            processor.processMessage(new DropIndexRequest(TestGlobals.DB, TestGlobals.COLL, "reindexField"));
        }
    }

    // REINDEX: rebuild only the specified field
    @Test
    public void test_reindex_specific_field_rebuilds_only_that_field() {
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "reindexFieldA"));
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "reindexFieldB"));
        try {
            ReindexRequest request = new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of("reindexFieldA"));
            ReindexResponse response = (ReindexResponse) processor.processMessage(request);
            assertEquals(OperationStatus.OK, response.getStatus());
            assertEquals(List.of("reindexFieldA"), response.getRebuiltFields());
        } finally {
            processor.processMessage(new DropIndexRequest(TestGlobals.DB, TestGlobals.COLL, "reindexFieldA"));
            processor.processMessage(new DropIndexRequest(TestGlobals.DB, TestGlobals.COLL, "reindexFieldB"));
        }
    }

    // REINDEX: returns NOT_FOUND when a specified field has no registered index
    @Test
    public void test_reindex_unknown_field_returns_error() {
        ReindexRequest request = new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of("noSuchIndex"));
        OperationResponse response = processor.processMessage(request);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-6", response.getErrorCode());
        assertTrue(response.getMessage().contains("noSuchIndex"));
    }
}
