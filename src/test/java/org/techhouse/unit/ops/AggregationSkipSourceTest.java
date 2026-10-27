package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationSkipSourceTest {
    private static final int DOCUMENTS = 150;
    private static final int PAGE = 20;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration configuration = Configuration.getInstance();
    private long shippedPageSize;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        shippedPageSize = configuration.getMaxPageSize();
        TestUtils.setPrivateField(configuration, "maxPageSize", 1024L);
        TestUtils.createTestDatabaseAndCollection();
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var doc = new JsonObject();
            doc.addProperty("_id", "s" + i);
            doc.addProperty("body", "text " + i + " " + "x".repeat(i % 5 * 20));
            objects.add(doc);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(objects);
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", shippedPageSize);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse run(boolean analyze, BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAnalyze(analyze);
        request.setAggregationSteps(List.of(steps));
        final var response = processor.processMessage(request);
        assertTrue(response.getStatus() == OperationStatus.OK || response.getStatus() == OperationStatus.NOT_FOUND,
                response.getMessage());
        return response;
    }

    private static List<String> ids(OperationResponse response) {
        if (!(response instanceof AggregateResponse aggregate) || aggregate.getResults() == null) {
            return List.of();
        }
        return aggregate.getResults().stream().map(row -> row.get("_id").asJsonString().getValue()).toList();
    }

    @Test
    public void test_skip_limit_pages_tile_the_whole_scan_without_gaps_or_duplicates() {
        final var paged = new ArrayList<String>();
        for (var skip = 0; skip < DOCUMENTS + PAGE; skip += PAGE) {
            paged.addAll(ids(run(false, new SkipAggregationStep(skip), new LimitAggregationStep(PAGE))));
        }

        final var whole = ids(run(false, new SkipAggregationStep(0)));

        assertEquals(DOCUMENTS, whole.size());
        assertEquals(whole, paged);
        assertEquals(DOCUMENTS, new HashSet<>(paged).size());
    }

    @Test
    public void test_a_source_skip_parses_fewer_documents_than_it_skips() {
        final var skip = DOCUMENTS - 10;

        final var response = (AggregateAnalyzeResponse) run(true, new SkipAggregationStep(skip),
                new LimitAggregationStep(5));

        assertEquals(5, response.getResults().size());
        assertTrue(response.getAnalyzeResult().getDocumentsScanned() < skip,
                "scanned " + response.getAnalyzeResult().getDocumentsScanned());
    }
}
