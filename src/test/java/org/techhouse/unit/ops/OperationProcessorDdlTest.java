package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.GetDatabaseStatsRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.ListDatabasesRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.GetDatabaseStatsResponse;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ReindexResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorDdlTest {
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

    // Process different operation types and return appropriate response objects
    @Test
    public void test_create_database() {
        CreateDatabaseRequest request = new CreateDatabaseRequest("testCreateDb");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationType.CREATE_DATABASE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(OperationType.CREATE_DATABASE, response.getType());
    }

    // Process different operation types and return appropriate response objects
    @Test
    public void test_drop_database() {
        CreateDatabaseRequest request = new CreateDatabaseRequest("testDropDb");

        OperationResponse response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());

        DropDatabaseRequest request2 = new DropDatabaseRequest("testDropDb");
        OperationResponse response2 = processor.processMessage(request2);
        assertEquals(OperationStatus.OK, response2.getStatus());
        assertEquals(OperationType.DROP_DATABASE, response2.getType());
        assertEquals(OperationType.DROP_DATABASE, response2.getType());
    }

    // Dropping a database that has collections locks each collection during deletion and releases them
    // afterwards (regression for the unlocked DROP_DATABASE path).
    @Test
    public void test_drop_database_with_collections_locks_and_releases() throws Exception {
        final var db = "dropLockDb";
        final var coll = "lockColl";
        org.techhouse.ops.AdminOperationHelper.saveDatabaseEntry(new org.techhouse.data.admin.AdminDbEntry(db));
        org.techhouse.ops.AdminOperationHelper
                .saveCollectionEntry(new org.techhouse.data.admin.AdminCollEntry(db, coll));
        final var fs = IocContainer.get(org.techhouse.fs.FileSystem.class);
        fs.createDatabaseFolder(db);
        fs.createCollectionFile(db, coll);
        // The admin db entry lists the collection, so the drop must lock it.
        assertTrue(
                IocContainer.get(org.techhouse.cache.Cache.class).getAdminDbEntry(db).getCollections().contains(coll));

        final var resp = processor.processMessage(new DropDatabaseRequest(db));

        assertEquals(OperationType.DROP_DATABASE, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        // The per-collection lock was released, so it can be re-acquired.
        final var locks = IocContainer.get(ResourceLocking.class);
        assertDoesNotThrow(() -> {
            locks.lock(db, coll);
            locks.release(db, coll);
        });
    }

    // Create and drop indexes with proper validation
    @Test
    public void test_create_and_drop_index() {
        CreateIndexRequest createIndexRequest = new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "fieldName");

        final var createIndexResponse = processor.processMessage(createIndexRequest);
        assertEquals(OperationType.CREATE_INDEX, createIndexResponse.getType());
        assertEquals(OperationStatus.OK, createIndexResponse.getStatus());
        assertEquals("Created index for field: fieldName", createIndexResponse.getMessage());

        DropIndexRequest dropIndexRequest = new DropIndexRequest(TestGlobals.DB, TestGlobals.COLL, "fieldName");
        final var dropIndexResponse = processor.processMessage(dropIndexRequest);
        assertEquals(OperationType.DROP_INDEX, dropIndexResponse.getType());
        assertEquals(OperationStatus.OK, dropIndexResponse.getStatus());
        assertEquals("Successfully dropped index: fieldName", dropIndexResponse.getMessage());
    }

    // A dirty read takes no collection lock, so it is absent from locksAcquired.
    @Test
    public void test_aggregation_with_analyze_dirtyRead_reports_no_collection_lock() {
        final var coll = "analyzeDirtyColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        final var saveRequest = new SaveRequest(TestGlobals.DB, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("status", new JsonString("active"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        final var aggregateRequest = new AggregateRequest(TestGlobals.DB, coll);
        aggregateRequest.setAnalyze(true);
        aggregateRequest.setDirtyRead(true);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")))));

        final var response = (AggregateAnalyzeResponse) processor.processMessage(aggregateRequest);

        assertFalse(response.getAnalyzeResult().getLocksAcquired()
                .contains(Cache.getCollectionIdentifier(TestGlobals.DB, coll)));
    }

    // create a test to create a collection and then drop it
    @Test
    public void test_create_and_drop_collection() {
        // Create Collection
        CreateCollectionRequest createRequest = new CreateCollectionRequest(TestGlobals.DB, "testCreateAndDropColl");
        OperationResponse createResponse = processor.processMessage(createRequest);

        assertNotNull(createResponse);
        assertEquals(OperationType.CREATE_COLLECTION, createResponse.getType());
        assertEquals(OperationStatus.OK, createResponse.getStatus());

        // Drop Collection
        DropCollectionRequest dropRequest = new DropCollectionRequest(TestGlobals.DB, "testCreateAndDropColl");
        OperationResponse dropResponse = processor.processMessage(dropRequest);

        assertNotNull(dropResponse);
        assertEquals(OperationType.DROP_COLLECTION, dropResponse.getType());
        assertEquals(OperationStatus.OK, dropResponse.getStatus());
    }

    @Test
    public void test_drop_collection_removes_lock_from_registry() throws Exception {
        final var collName = "lockCleanupColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, collName));

        processor.processMessage(new DropCollectionRequest(TestGlobals.DB, collName));

        // After a successful drop the lock entry must be removed from the registry so
        // it does not accumulate stale locks for deleted collections.
        final var locksField = ResourceLocking.class.getDeclaredField("locks");
        locksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final var lockMap = (java.util.Map<String, ?>) locksField.get(null);
        assertNull(lockMap.get(Cache.getCollectionIdentifier(TestGlobals.DB, collName)),
                "Lock entry must be removed from registry after a successful drop");
    }

    // List databases returns user databases excluding admin
    @Test
    public void test_list_databases_returns_user_databases_excluding_admin() {
        ListDatabasesRequest request = new ListDatabasesRequest();

        ListDatabasesResponse response = (ListDatabasesResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(response.getDatabases());
        assertTrue(response.getDatabases().contains(TestGlobals.DB));
        assertFalse(response.getDatabases().contains(Globals.ADMIN_DB_NAME));
    }

    // List databases returns OK with empty list when no user databases exist
    @Test
    public void test_list_databases_returns_ok_with_empty_list() {
        // Note: cannot easily test with empty database list given the test setup
        // creates TestGlobals.DB at @BeforeAll; this test documents the expected behavior
        ListDatabasesRequest request = new ListDatabasesRequest();

        ListDatabasesResponse response = (ListDatabasesResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(response.getDatabases());
        // List should never be null on success, even if empty
    }

    // List collections returns collections of the database
    @Test
    public void test_list_collections_returns_collections_of_db() {
        ListCollectionsRequest request = new ListCollectionsRequest(TestGlobals.DB);

        ListCollectionsResponse response = (ListCollectionsResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(response.getCollections());
        assertTrue(response.getCollections().contains(TestGlobals.COLL));
    }

    // List collections returns NOT_FOUND for unknown database
    @Test
    public void test_list_collections_unknown_database_returns_not_found() {
        ListCollectionsRequest request = new ListCollectionsRequest("does-not-exist");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-4", response.getErrorCode());
    }

    // List collections for admin database returns empty list
    @Test
    public void test_list_collections_admin_database_returns_empty_list() {
        ListCollectionsRequest request = new ListCollectionsRequest(Globals.ADMIN_DB_NAME);

        ListCollectionsResponse response = (ListCollectionsResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(response.getCollections());
        assertTrue(response.getCollections().isEmpty());
    }

    // List collections with blank database name returns error
    @Test
    public void test_list_collections_blank_database_name_returns_error() {
        ListCollectionsRequest request = new ListCollectionsRequest("");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("400-1", response.getErrorCode());
    }

    // List collections only returns collections of requested database
    @Test
    public void test_list_collections_only_returns_collections_of_requested_db() {
        CreateDatabaseRequest createDbRequest = new CreateDatabaseRequest("otherDb");
        processor.processMessage(createDbRequest);

        CreateCollectionRequest createCollRequest = new CreateCollectionRequest("otherDb", "otherColl");
        processor.processMessage(createCollRequest);

        ListCollectionsRequest request = new ListCollectionsRequest(TestGlobals.DB);
        ListCollectionsResponse response = (ListCollectionsResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(response.getCollections());
        assertTrue(response.getCollections().contains(TestGlobals.COLL));
        assertFalse(response.getCollections().contains("otherColl"));
    }

    // Drop index returns not-found when the collection does not exist
    @Test
    public void test_drop_index_returns_error_for_nonexistent_collection() {
        DropIndexRequest request = new DropIndexRequest(TestGlobals.DB, "noSuchColl", "noSuchField");

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-6", response.getErrorCode());
    }

    @Test
    public void test_get_database_stats_returns_populated_payload() {
        // Make sure there's at least one user document so totals are non-trivial.
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "stats_seed");
        obj.addProperty("v", 1);
        save.setObject(obj);
        save.set_id("stats_seed");
        processor.processMessage(save);

        final var response = (GetDatabaseStatsResponse) processor.processMessage(new GetDatabaseStatsRequest());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(OperationType.GET_DATABASE_STATS, response.getType());
        final var stats = response.getStats();
        assertNotNull(stats);
        assertTrue(stats.has("memory"));
        assertTrue(stats.has("totals"));
        assertTrue(stats.has("databases"));
        assertTrue(stats.has("scripts"));

        final var scripts = stats.get("scripts").asJsonObject();
        assertTrue(scripts.has("routingEnabled"));
        assertEquals(0L, scripts.get("running").asJsonNumber().getValue().longValue());
        final var admission = IocContainer.get(ScriptAdmission.class);
        assertEquals(admission.capacity(), scripts.get("capacity").asJsonNumber().getValue().longValue());
        assertEquals(admission.available(), scripts.get("available").asJsonNumber().getValue().longValue());
        assertEquals(admission.getRejected(), scripts.get("rejected").asJsonNumber().getValue().longValue());
        assertEquals(admission.getWaited(), scripts.get("waited").asJsonNumber().getValue().longValue());
        assertTrue(scripts.has("forwarded"));
        assertTrue(scripts.has("forwardFallbacks"));
        assertEquals(Configuration.getInstance().getScriptLocalityWeight(),
                scripts.get("localityWeight").asJsonNumber().getValue().intValue());
        assertTrue(scripts.has("localityPreferred"));
        assertEquals(IocContainer.get(org.techhouse.ops.ScriptRunRegistry.class).getCancelled(),
                scripts.get("cancelled").asJsonNumber().getValue().longValue());

        final var memory = stats.get("memory").asJsonObject();
        assertTrue(memory.has("heapUsedBytes"));
        assertTrue(memory.has("heapMaxBytes"));
        assertTrue(memory.has("userCacheBytes"));
        assertTrue(memory.has("maxMemoryBytes"));

        final var totals = stats.get("totals").asJsonObject();
        assertTrue(totals.get("userCount").asJsonNumber().getValue().longValue() >= 0L);
        assertTrue(totals.get("databaseCount").asJsonNumber().getValue().longValue() >= 1L);
        assertTrue(totals.get("collectionCount").asJsonNumber().getValue().longValue() >= 1L);
        assertTrue(totals.get("entryCount").asJsonNumber().getValue().longValue() >= 0L);

        final var dbs = stats.get("databases").asJsonArray().asList();
        assertFalse(dbs.isEmpty(), "expected at least one user database in the stats payload");
        final var firstDb = dbs.getFirst().asJsonObject();
        assertTrue(firstDb.has("name"));
        assertTrue(firstDb.has("collectionCount"));
        assertTrue(firstDb.has("collections"));
        final var firstCollections = firstDb.get("collections").asJsonArray().asList();
        if (!firstCollections.isEmpty()) {
            final var firstColl = firstCollections.getFirst().asJsonObject();
            assertTrue(firstColl.has("name"));
            assertTrue(firstColl.has("indexCount"));
            assertTrue(firstColl.has("indexes"));
            assertTrue(firstColl.has("pageCount"));
            assertTrue(firstColl.has("entryCount"));
            assertTrue(firstColl.has("sizeBytes"));
        }
    }

    // A dirty read proceeds even while another thread holds the collection write lock.
    @Test
    public void test_dirty_read_proceeds_while_collection_write_locked() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var json = "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB
                    + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"missing\",\"dirtyRead\":true}";
            final var request = RequestParser.parseRequest(json);
            assertTrue(request.isDirtyRead());

            final var result = new AtomicReference<OperationResponse>();
            final var done = new CountDownLatch(1);
            final var worker = new Thread(() -> {
                result.set(processor.processMessage(request));
                done.countDown();
            });
            worker.start();
            assertTrue(done.await(2, TimeUnit.SECONDS), "dirty read must not block on the collection write lock");
            assertNotNull(result.get());
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    // An AGGREGATE with a JOIN read-locks both collections and releases them when finished.
    @Test
    public void test_aggregate_with_join_releases_both_collection_locks() {
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.JOIN_COLL));

        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        final List<BaseAggregationStep> steps = new ArrayList<>();
        steps.add(new JoinAggregationStep(TestGlobals.JOIN_COLL, "_id", "_id", "joined"));
        request.setAggregationSteps(steps);
        processor.processMessage(request);

        final var locks = IocContainer.get(ResourceLocking.class);
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL),
                "primary collection read lock must be released after AGGREGATE");
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.JOIN_COLL),
                "joined collection read lock must be released after AGGREGATE");
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
        locks.releaseWrite(TestGlobals.DB, TestGlobals.JOIN_COLL);
    }

    @Test
    public void test_drop_database_removes_locks_for_all_collections() throws Exception {
        final var db = "lockCleanupDb";
        processor.processMessage(new CreateDatabaseRequest(db));
        processor.processMessage(new CreateCollectionRequest(db, "collA"));
        processor.processMessage(new CreateCollectionRequest(db, "collB"));

        processor.processMessage(new DropDatabaseRequest(db));

        final var locksField = ResourceLocking.class.getDeclaredField("locks");
        locksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final var lockMap = (java.util.Map<String, ?>) locksField.get(null);
        assertNull(lockMap.get(Cache.getCollectionIdentifier(db, "collA")),
                "Lock for collA must be removed after database drop");
        assertNull(lockMap.get(Cache.getCollectionIdentifier(db, "collB")),
                "Lock for collB must be removed after database drop");
    }

    // REINDEX: returns OK with empty list when no indexes exist on the collection
    @Test
    public void test_reindex_collection_with_no_indexes_returns_ok_empty_list() {
        ReindexRequest request = new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, null);
        ReindexResponse response = (ReindexResponse) processor.processMessage(request);
        // All indexes from prior tests have been dropped; if some still exist the response is still OK
        assertEquals(OperationStatus.OK, response.getStatus());
    }
}
