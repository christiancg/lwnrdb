package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CloseConnectionRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.CloseConnectionResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorReadTest {
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

    @Test
    public void test_process_message_returns_correct_response_type() {
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        saveRequest.setObject(new JsonObject());

        OperationResponse response = processor.processMessage(saveRequest);

        assertNotNull(response);
        assertInstanceOf(SaveResponse.class, response);
        assertEquals(OperationType.SAVE, response.getType());
    }

    @Test
    public void test_find_by_id_returns_not_found_for_nonexistent_entry() {
        FindByIdRequest request = new FindByIdRequest("nonexistentDb", "nonexistentColl");
        request.set_id("123");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-2", response.getErrorCode());
    }

    @Test
    public void test_find_by_id_operation_success() {
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        var obj = new JsonObject();
        obj.add("_id", new JsonString("123"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        FindByIdRequest request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("123");

        FindByIdResponse response = (FindByIdResponse) processor.processMessage(request);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(obj, response.getObject());
    }

    @Test
    public void test_process_aggregation_request() {
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        var obj = new JsonObject();
        obj.add("_id", new JsonString("123"));
        obj.add("searchMe", new JsonString("test"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        AggregateRequest aggregateRequest = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "searchMe", new JsonString("test")))));

        AggregateResponse aggregateResponse = (AggregateResponse) processor.processMessage(aggregateRequest);

        assertEquals(OperationStatus.OK, aggregateResponse.getStatus());
        assertEquals("Ok", aggregateResponse.getMessage());
        assertNotNull(aggregateResponse.getResults());
        assertEquals(1, aggregateResponse.getResults().size());
    }

    @Test
    public void test_aggregation_without_analyze_has_no_analyzeResult() {
        final var coll = "analyzeOffColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        final var saveRequest = new SaveRequest(TestGlobals.DB, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("status", new JsonString("active"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        final var aggregateRequest = new AggregateRequest(TestGlobals.DB, coll);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")))));

        final var response = processor.processMessage(aggregateRequest);

        // Exactly AggregateResponse — not the analyze subclass — so no analyzeResult is serialized.
        assertEquals(AggregateResponse.class, response.getClass());
    }

    @Test
    public void test_aggregation_with_analyze_returns_analyzeResult() {
        final var coll = "analyzeOnColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        final var saveRequest = new SaveRequest(TestGlobals.DB, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("status", new JsonString("active"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        final var aggregateRequest = new AggregateRequest(TestGlobals.DB, coll);
        aggregateRequest.setAnalyze(true);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")))));

        final var response = (AggregateAnalyzeResponse) processor.processMessage(aggregateRequest);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(1, response.getResults().size());
        final var analyzeResult = response.getAnalyzeResult();
        assertNotNull(analyzeResult);
        assertTrue(analyzeResult.getDocumentsScanned() > 0);
        assertTrue(analyzeResult.getLocksAcquired().contains(Cache.getCollectionIdentifier(TestGlobals.DB, coll)));
    }

    @Test
    public void test_aggregation_with_analyze_suggests_moving_filter() {
        final var coll = "analyzeMoveFilterColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        final var saveRequest = new SaveRequest(TestGlobals.DB, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("status", new JsonString("active"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        final var aggregateRequest = new AggregateRequest(TestGlobals.DB, coll);
        aggregateRequest.setAnalyze(true);
        aggregateRequest.setAggregationSteps(List.of(new SortAggregationStep("status", true), new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")))));

        final var response = (AggregateAnalyzeResponse) processor.processMessage(aggregateRequest);

        assertTrue(response.getAnalyzeResult().getSuggestions().stream()
                .anyMatch(s -> s.startsWith("FILTER step") && s.contains("step 2")));
    }

    @Test
    public void test_aggregation_with_analyze_empty_results_still_has_analyzeResult() {
        final var coll = "analyzeEmptyColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        final var saveRequest = new SaveRequest(TestGlobals.DB, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("status", new JsonString("active"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        final var aggregateRequest = new AggregateRequest(TestGlobals.DB, coll);
        aggregateRequest.setAnalyze(true);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("nope")))));

        final var response = processor.processMessage(aggregateRequest);

        assertInstanceOf(AggregateAnalyzeResponse.class, response);
        final var analyzeResponse = (AggregateAnalyzeResponse) response;
        assertEquals(OperationStatus.OK, analyzeResponse.getStatus());
        assertTrue(analyzeResponse.getResults().isEmpty());
        assertNotNull(analyzeResponse.getAnalyzeResult());
    }

    @Test
    public void test_aggregate_element_match_object_and_array() {
        final var coll = "elementMatchColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));

        for (var spec : List.of(new String[]{"em1", "obj", "1"}, new String[]{"em2", "obj", "1"},
                new String[]{"em3", "obj", "2"}, new String[]{"em4", "arr", "x"})) {
            final var saveRequest = new SaveRequest(TestGlobals.DB, coll);
            final var obj = new JsonObject();
            obj.add(Globals.PK_FIELD, new JsonString(spec[0]));
            if ("obj".equals(spec[1])) {
                final var inner = new JsonObject();
                inner.addProperty("n", Integer.valueOf(spec[2]));
                obj.add("payload", inner);
            } else {
                final var arr = new JsonArray();
                arr.add(new JsonString(spec[2]));
                obj.add("payload", arr);
            }
            saveRequest.setObject(obj);
            assertEquals(OperationStatus.OK, processor.processMessage(saveRequest).getStatus());
        }

        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "payload"));

        final var objQuery = new JsonObject();
        objQuery.addProperty("n", 1);
        final var objAgg = new AggregateRequest(TestGlobals.DB, coll);
        objAgg.setAggregationSteps(
                List.of(new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "payload", objQuery))));
        final var objResp = (AggregateResponse) processor.processMessage(objAgg);
        assertEquals(OperationStatus.OK, objResp.getStatus());
        assertEquals(2, objResp.getResults().size());

        final var arrQuery = new JsonArray();
        arrQuery.add(new JsonString("x"));
        final var arrAgg = new AggregateRequest(TestGlobals.DB, coll);
        arrAgg.setAggregationSteps(
                List.of(new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "payload", arrQuery))));
        final var arrResp = (AggregateResponse) processor.processMessage(arrAgg);
        assertEquals(OperationStatus.OK, arrResp.getStatus());
        assertEquals(1, arrResp.getResults().size());

        processor.processMessage(new DropCollectionRequest(TestGlobals.DB, coll));
    }

    @Test
    public void test_close_connection_returns_correct_response() {
        CloseConnectionRequest request = new CloseConnectionRequest();

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertInstanceOf(CloseConnectionResponse.class, response);
        assertEquals(OperationType.CLOSE_CONNECTION, response.getType());
    }

    @Test
    public void test_aggregate_returns_not_found_when_no_results() {
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "nobody", new JsonString("nope")))));

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-3", response.getErrorCode());
    }

    @Test
    public void test_find_by_id_releases_read_lock() {
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("lock-id-1"));
        save.setObject(obj);
        processor.processMessage(save);

        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("lock-id-1");
        final var response = (FindByIdResponse) processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());

        final var locks = IocContainer.get(ResourceLocking.class);
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL),
                "read lock must be released after FIND_BY_ID completes");
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_normal_read_blocks_until_write_released() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("missing");
        final var done = new CountDownLatch(1);
        final var worker = new Thread(() -> {
            processor.processMessage(request);
            done.countDown();
        });
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        boolean releasedWrite = false;
        try {
            worker.start();
            assertFalse(done.await(300, TimeUnit.MILLISECONDS),
                    "a locking read must block while a writer holds the collection");
            locks.release(TestGlobals.DB, TestGlobals.COLL);
            releasedWrite = true;
            assertTrue(done.await(2, TimeUnit.SECONDS),
                    "the read must proceed once the writer releases the collection");
        } finally {
            if (!releasedWrite) {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        }
    }

    // After a grow-relocation, the document's id must remain in the pending overlay even after the
    // DELETED event's worker clears its mark, so the CREATED event's worker can still clear it and
    // index-backed queries never see a false negative in the window between the two events.
    @Test
    public void test_relocate_keeps_id_pending_until_both_events_processed() throws Exception {
        final var config = org.techhouse.config.Configuration.getInstance();
        final var originalMaxPage = config.getMaxPageSize();
        final var originalMaxEntry = config.getMaxEntrySize();
        final var collName = "pendingRelocateColl";
        TestUtils.setPrivateField(config, "maxPageSize", 2000L);
        TestUtils.setPrivateField(config, "maxEntrySize", 100_000L);
        // Swap in a fresh BackgroundTaskManager whose workers are never started so the relocation's
        // DELETED/CREATED events sit unprocessed in its queue. Otherwise, if another test class
        // (e.g. MainTest) has already started the shared IoC manager's workers, they would drain the
        // events and clear the pending marks before this assertion runs, making the test flaky.
        final var originalTaskManager = TestUtils.getPrivateStaticField(SaveOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", new BackgroundTaskManager());
        try {
            processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, collName));

            final var keepSave = new SaveRequest(TestGlobals.DB, collName);
            final var keepObj = new JsonObject();
            keepObj.add(Globals.PK_FIELD, new JsonString("keep"));
            keepObj.add("v", new JsonString("k".repeat(280)));
            keepSave.setObject(keepObj);
            keepSave.set_id("keep");
            processor.processMessage(keepSave);

            final var aSave = new SaveRequest(TestGlobals.DB, collName);
            final var aObj = new JsonObject();
            aObj.add(Globals.PK_FIELD, new JsonString("a"));
            aObj.add("v", new JsonString("small"));
            aSave.setObject(aObj);
            aSave.set_id("a");
            processor.processMessage(aSave);

            final var pending = IocContainer.get(PendingIndexWrites.class);

            final var growSave = new SaveRequest(TestGlobals.DB, collName);
            final var grown = new JsonObject();
            grown.add(Globals.PK_FIELD, new JsonString("a"));
            grown.add("v", new JsonString("x".repeat(1780)));
            growSave.setObject(grown);
            growSave.set_id("a");
            processor.processMessage(growSave);

            assertTrue(pending.idsFor(TestGlobals.DB, collName).contains("a"),
                    "id must remain pending after relocation so both events are covered");
        } finally {
            TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", originalTaskManager);
            TestUtils.setPrivateField(config, "maxPageSize", originalMaxPage);
            TestUtils.setPrivateField(config, "maxEntrySize", originalMaxEntry);
            processor.processMessage(new DropCollectionRequest(TestGlobals.DB, collName));
        }
    }
}
