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
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CloseConnectionRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
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
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.CloseConnectionResponse;
import org.techhouse.ops.resp.CreateCollectionResponse;
import org.techhouse.ops.resp.CreateDatabaseResponse;
import org.techhouse.ops.resp.CreateIndexResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.DropCollectionResponse;
import org.techhouse.ops.resp.DropDatabaseResponse;
import org.techhouse.ops.resp.DropIndexResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.GetDatabaseStatsResponse;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ReindexResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorTest {
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
    public void test_process_message_returns_correct_response_type() {
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        saveRequest.setObject(new JsonObject());

        OperationResponse response = processor.processMessage(saveRequest);

        assertNotNull(response);
        assertInstanceOf(SaveResponse.class, response);
        assertEquals(OperationType.SAVE, response.getType());
    }

    // Handle non-existent database/collection operations
    @Test
    public void test_find_by_id_returns_not_found_for_nonexistent_entry() {
        FindByIdRequest request = new FindByIdRequest("nonexistentDb", "nonexistentColl");
        request.set_id("123");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-2", response.getErrorCode());
    }

    // Find entries by ID and return results with correct status
    @Test
    public void test_find_by_id_operation_success() {
        // Arrange
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        var obj = new JsonObject();
        obj.add("_id", new JsonString("123"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        FindByIdRequest request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("123");

        // Act
        FindByIdResponse response = (FindByIdResponse) processor.processMessage(request);

        // Assert
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(obj, response.getObject());
    }

    // Delete entries and update cache/indexes accordingly
    @Test
    public void test_delete_operation_success() {
        // Arrange
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        var obj = new JsonObject();
        obj.add("_id", new JsonString("123"));
        saveRequest.setObject(obj);
        final var saveResponse = processor.processMessage(saveRequest);
        assertNotNull(saveResponse);
        assertEquals(OperationStatus.OK, saveResponse.getStatus());

        // Act
        DeleteRequest request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("123");
        DeleteResponse response = (DeleteResponse) processor.processMessage(request);

        // Assert
        assertEquals(OperationStatus.OK, response.getStatus());
    }

    // Handle duplicate IDs in bulk save operations
    @Test
    public void test_bulk_save() {
        BulkSaveRequest request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        List<JsonObject> objects = new ArrayList<>();

        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, "id1");
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, "id2");

        objects.add(obj1);
        objects.add(obj2);
        request.setObjects(objects);

        BulkSaveResponse response = (BulkSaveResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(2, response.getInserted().size());
        assertTrue(response.getUpdated().isEmpty());
    }

    // Process different operation types and return appropriate response objects
    @Test
    public void test_create_database() {
        CreateDatabaseRequest request = new CreateDatabaseRequest("testCreateDb");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationType.CREATE_DATABASE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertInstanceOf(CreateDatabaseResponse.class, response);
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
        assertInstanceOf(DropDatabaseResponse.class, response2);
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

        final var resp = (DropDatabaseResponse) processor.processMessage(new DropDatabaseRequest(db));

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

        CreateIndexResponse createIndexResponse = (CreateIndexResponse) processor.processMessage(createIndexRequest);
        assertEquals(OperationStatus.OK, createIndexResponse.getStatus());
        assertEquals("Created index for field: fieldName", createIndexResponse.getMessage());

        DropIndexRequest dropIndexRequest = new DropIndexRequest(TestGlobals.DB, TestGlobals.COLL, "fieldName");
        DropIndexResponse dropIndexResponse = (DropIndexResponse) processor.processMessage(dropIndexRequest);
        assertEquals(OperationStatus.OK, dropIndexResponse.getStatus());
        assertEquals("Successfully dropped index: fieldName", dropIndexResponse.getMessage());
    }

    // Process aggregation requests and return results
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

    // End-to-end: save docs with object/array fields, index them, then filter by element-match
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

        // Object element-match: {n:1} matches em1 and em2 only
        final var objQuery = new JsonObject();
        objQuery.addProperty("n", 1);
        final var objAgg = new AggregateRequest(TestGlobals.DB, coll);
        objAgg.setAggregationSteps(
                List.of(new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "payload", objQuery))));
        final var objResp = (AggregateResponse) processor.processMessage(objAgg);
        assertEquals(OperationStatus.OK, objResp.getStatus());
        assertEquals(2, objResp.getResults().size());

        // Array element-match: ["x"] matches em4 only
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

    // create a test to create a collection and then drop it
    @Test
    public void test_create_and_drop_collection() {
        // Create Collection
        CreateCollectionRequest createRequest = new CreateCollectionRequest(TestGlobals.DB, "testCreateAndDropColl");
        OperationResponse createResponse = processor.processMessage(createRequest);

        assertNotNull(createResponse);
        assertInstanceOf(CreateCollectionResponse.class, createResponse);
        assertEquals(OperationStatus.OK, createResponse.getStatus());

        // Drop Collection
        DropCollectionRequest dropRequest = new DropCollectionRequest(TestGlobals.DB, "testCreateAndDropColl");
        OperationResponse dropResponse = processor.processMessage(dropRequest);

        assertNotNull(dropResponse);
        assertInstanceOf(DropCollectionResponse.class, dropResponse);
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

    // After a save, the entry's PkIndexEntry carries the page assigned by selectPageForInsert
    @Test
    public void test_save_operation_assigns_page() throws Exception {
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, "testPageAssignedId");
        saveRequest.setObject(obj);

        SaveResponse response = (SaveResponse) processor.processMessage(saveRequest);
        assertEquals(OperationStatus.OK, response.getStatus());

        final var cache = IocContainer.get(org.techhouse.cache.Cache.class);
        final var pkIdx = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        final var saved = pkIdx.stream().filter(p -> p.getValue().equals("testPageAssignedId")).findFirst();
        assertTrue(saved.isPresent());
        // First insert into a small collection lands on page 0
        assertEquals(0L, saved.get().getPage());
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

    // CLOSE_CONNECTION returns a CloseConnectionResponse
    @Test
    public void test_close_connection_returns_correct_response() {
        CloseConnectionRequest request = new CloseConnectionRequest();

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertInstanceOf(CloseConnectionResponse.class, response);
        assertEquals(OperationType.CLOSE_CONNECTION, response.getType());
    }

    // Save with an existing _id updates the entry rather than inserting a duplicate
    @Test
    public void test_save_operation_updates_existing_entry() {
        SaveRequest firstSave = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("updateMe"));
        obj.add("value", new JsonNumber(1));
        firstSave.setObject(obj);
        firstSave.set_id("updateMe");
        SaveResponse firstResponse = (SaveResponse) processor.processMessage(firstSave);
        assertEquals(OperationStatus.OK, firstResponse.getStatus());

        SaveRequest secondSave = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject updated = new JsonObject();
        updated.add(Globals.PK_FIELD, new JsonString("updateMe"));
        updated.add("value", new JsonNumber(2));
        secondSave.setObject(updated);
        secondSave.set_id("updateMe");
        SaveResponse secondResponse = (SaveResponse) processor.processMessage(secondSave);

        assertEquals(OperationStatus.OK, secondResponse.getStatus());
        assertEquals("updateMe", secondResponse.get_id());
    }

    // Delete returns NOT_FOUND when the entry does not exist
    @Test
    public void test_delete_returns_not_found_for_missing_entry() {
        DeleteRequest request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("no-such-id");

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-2", response.getErrorCode());
    }

    // Aggregate returns NOT_FOUND when no documents match
    @Test
    public void test_aggregate_returns_not_found_when_no_results() {
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "nobody", new JsonString("nope")))));

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-3", response.getErrorCode());
    }

    // Bulk save with some already-existing IDs performs updates for those entries
    @Test
    public void test_bulk_save_updates_existing_entries() {
        SaveRequest insert = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("bulkExisting"));
        obj.add("v", new JsonNumber(1));
        insert.setObject(obj);
        insert.set_id("bulkExisting");
        processor.processMessage(insert);

        BulkSaveRequest bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject updated = new JsonObject();
        updated.add(Globals.PK_FIELD, new JsonString("bulkExisting"));
        updated.add("v", new JsonNumber(2));
        JsonObject newOne = new JsonObject();
        newOne.add(Globals.PK_FIELD, new JsonString("bulkNew"));
        bulk.setObjects(List.of(updated, newOne));

        BulkSaveResponse response = (BulkSaveResponse) processor.processMessage(bulk);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(response.getInserted().contains("bulkNew"));
    }

    // Drop index returns not-found when the collection does not exist
    @Test
    public void test_drop_index_returns_error_for_nonexistent_collection() {
        DropIndexRequest request = new DropIndexRequest(TestGlobals.DB, "noSuchColl", "noSuchField");

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-6", response.getErrorCode());
    }

    // Save an oversized entry returns an error response
    @Test
    public void test_save_oversized_entry_returns_error() {
        SaveRequest request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("bigId"));
        // Create a value larger than 1MB (maxEntrySize default)
        obj.add("bigField", new JsonString("x".repeat(1_048_600)));
        request.setObject(obj);

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("400-2", response.getErrorCode());
    }

    // Bulk save with duplicate _id values in the same request returns an error
    @Test
    public void test_bulk_save_duplicate_id_returns_error() {
        BulkSaveRequest request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("dupId"));
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("dupId"));
        request.setObjects(List.of(obj1, obj2));

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("400-3", response.getErrorCode());
        assertTrue(response.getMessage().contains("dupId"));
    }

    // Bulk save with an oversized entry returns an error response
    @Test
    public void test_bulk_save_oversized_entry_returns_error() {
        BulkSaveRequest request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("bigId2"));
        obj.add("bigField", new JsonString("x".repeat(1_048_600)));
        request.setObjects(List.of(obj));

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("400-2", response.getErrorCode());
    }

    @Test
    public void test_save_records_collection_usage() {
        final var mm = IocContainer.get(org.techhouse.cache.MemoryManagement.class);
        final var before = mm.getCounter(org.techhouse.cache.AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL,
                null);
        final var beforeCount = before == null ? 0L : before.getAccessCount();
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("usage-id-1"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);
        final var after = mm.getCounter(org.techhouse.cache.AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL,
                null);
        assertNotNull(after);
        assertTrue(after.getAccessCount() > beforeCount);
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

    @Test
    public void test_save_admin_collection_does_not_record_usage() {
        final var mm = IocContainer.get(org.techhouse.cache.MemoryManagement.class);
        // admin saves go through helpers, but explicitly verify recordAccess no ops:
        mm.recordAccess(org.techhouse.cache.AccessKind.COLLECTION, Globals.ADMIN_DB_NAME, "databases", null);
        assertNull(mm.getCounter(org.techhouse.cache.AccessKind.COLLECTION, Globals.ADMIN_DB_NAME, "databases", null));
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

    // A successful FIND_BY_ID releases its collection read lock (a writer can lock afterward).
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

    // A normal (locking) read blocks while a writer holds the collection, then proceeds once released.
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

    // A SAVE that grows an existing document past maxPageSize relocates it to another page instead of
    // overflowing its current page; the document still reads back intact and no page exceeds the cap.
    @Test
    public void test_save_grow_update_relocates_to_avoid_page_overflow() throws Exception {
        final var cache = IocContainer.get(org.techhouse.cache.Cache.class);
        final var config = org.techhouse.config.Configuration.getInstance();
        final var originalMaxPage = config.getMaxPageSize();
        final var originalMaxEntry = config.getMaxEntrySize();
        final var collName = "overflowColl";
        TestUtils.setPrivateField(config, "maxPageSize", 2000L);
        TestUtils.setPrivateField(config, "maxEntrySize", 100_000L);
        try {
            processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, collName));

            // "keep" (~300 B) plus a small "a", co-located on page 0. Sized so that growing "a" to ~1.8 KB
            // makes page 0 overflow maxPageSize (keepBytes + newBytes > 2000) yet the grown "a" still fits
            // on a fresh page (newBytes < 2000), forcing a relocation rather than an in-place rewrite.
            final var keepSave = new SaveRequest(TestGlobals.DB, collName);
            final var keepObj = new JsonObject();
            keepObj.add(Globals.PK_FIELD, new JsonString("keep"));
            keepObj.add("v", new JsonString("k".repeat(280)));
            keepSave.setObject(keepObj);
            keepSave.set_id("keep");
            assertEquals(OperationStatus.OK, processor.processMessage(keepSave).getStatus());

            final var aSave = new SaveRequest(TestGlobals.DB, collName);
            final var aObj = new JsonObject();
            aObj.add(Globals.PK_FIELD, new JsonString("a"));
            aObj.add("v", new JsonString("small"));
            aSave.setObject(aObj);
            aSave.set_id("a");
            assertEquals(OperationStatus.OK, processor.processMessage(aSave).getStatus());

            final var pkIndex = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, collName);
            final var keepPageBefore = pkIndex.stream().filter(p -> p.getValue().equals("keep")).findFirst()
                    .orElseThrow().getPage();
            final var aPageBefore = pkIndex.stream().filter(p -> p.getValue().equals("a")).findFirst().orElseThrow()
                    .getPage();
            assertEquals(0L, keepPageBefore);
            assertEquals(0L, aPageBefore, "both docs must start co-located on page 0");

            // Grow "a" past what fits on page 0 alongside "keep" -> must relocate.
            final var bigValue = "x".repeat(1780);
            final var growSave = new SaveRequest(TestGlobals.DB, collName);
            final var grown = new JsonObject();
            grown.add(Globals.PK_FIELD, new JsonString("a"));
            grown.add("v", new JsonString(bigValue));
            growSave.setObject(grown);
            growSave.set_id("a");
            assertEquals(OperationStatus.OK, processor.processMessage(growSave).getStatus());

            // "a" relocated off page 0; "keep" stayed put.
            final var pkAfter = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, collName);
            final var aPageAfter = pkAfter.stream().filter(p -> p.getValue().equals("a")).findFirst().orElseThrow()
                    .getPage();
            final var keepPageAfter = pkAfter.stream().filter(p -> p.getValue().equals("keep")).findFirst()
                    .orElseThrow().getPage();
            assertEquals(0L, keepPageAfter, "the untouched doc must stay on page 0");
            assertTrue(aPageAfter > 0L, "the grown doc must relocate off page 0 (was " + aPageAfter + ")");

            // The grown value reads back intact.
            final var find = new FindByIdRequest(TestGlobals.DB, collName);
            find.set_id("a");
            final var found = (FindByIdResponse) processor.processMessage(find);
            assertEquals(OperationStatus.OK, found.getStatus());
            assertEquals(bigValue, found.getObject().get("v").asJsonString().getValue());

            // No on-disk page file exceeds maxPageSize.
            final var collFolder = new java.io.File(
                    TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + collName);
            final var datFiles = collFolder.listFiles((_, n) -> n.endsWith(Globals.DB_FILE_EXTENSION));
            assertNotNull(datFiles);
            for (final var dat : datFiles) {
                assertTrue(dat.length() <= 2000L,
                        "page file " + dat.getName() + " (" + dat.length() + " bytes) must not exceed maxPageSize");
            }
        } finally {
            TestUtils.setPrivateField(config, "maxPageSize", originalMaxPage);
            TestUtils.setPrivateField(config, "maxEntrySize", originalMaxEntry);
            processor.processMessage(new DropCollectionRequest(TestGlobals.DB, collName));
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
        final var originalTaskManager = TestUtils.getPrivateField(processor, "taskManager",
                BackgroundTaskManager.class);
        TestUtils.setPrivateField(processor, "taskManager", new BackgroundTaskManager());
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

            // Trigger a relocation: grow "a" so page 0 overflows.
            final var growSave = new SaveRequest(TestGlobals.DB, collName);
            final var grown = new JsonObject();
            grown.add(Globals.PK_FIELD, new JsonString("a"));
            grown.add("v", new JsonString("x".repeat(1780)));
            growSave.setObject(grown);
            growSave.set_id("a");
            processor.processMessage(growSave);

            // Immediately after processMessage returns, "a" must be pending with count >= 2
            // (one mark for DELETED, one for CREATED) so that the DELETED worker's clear()
            // cannot drop the key before CREATED is processed.
            assertTrue(pending.idsFor(TestGlobals.DB, collName).contains("a"),
                    "id must remain pending after relocation so both events are covered");
        } finally {
            TestUtils.setPrivateField(processor, "taskManager", originalTaskManager);
            TestUtils.setPrivateField(config, "maxPageSize", originalMaxPage);
            TestUtils.setPrivateField(config, "maxEntrySize", originalMaxEntry);
            processor.processMessage(new DropCollectionRequest(TestGlobals.DB, collName));
        }
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

    // When no admin page metadata is available, the overflow check can't assess the page, so the SAVE
    // falls back to an in-place update (no relocation) and the document still updates correctly.
    @Test
    public void test_save_grow_update_without_page_metadata_updates_in_place() {
        final var cache = IocContainer.get(org.techhouse.cache.Cache.class);
        final var collName = "noPageMetaColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, collName));
        try {
            final var save = new SaveRequest(TestGlobals.DB, collName);
            final var o = new JsonObject();
            o.add(Globals.PK_FIELD, new JsonString("x"));
            o.add("v", new JsonString("one"));
            save.setObject(o);
            save.set_id("x");
            assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());

            // Drop the in-memory page metadata so wouldOverflowPage hits its null fallback.
            cache.removeAdminPageEntries(TestGlobals.DB, collName);

            final var upd = new SaveRequest(TestGlobals.DB, collName);
            final var o2 = new JsonObject();
            o2.add(Globals.PK_FIELD, new JsonString("x"));
            o2.add("v", new JsonString("two"));
            upd.setObject(o2);
            upd.set_id("x");
            assertEquals(OperationStatus.OK, processor.processMessage(upd).getStatus());

            final var find = new FindByIdRequest(TestGlobals.DB, collName);
            find.set_id("x");
            final var found = (FindByIdResponse) processor.processMessage(find);
            assertEquals(OperationStatus.OK, found.getStatus());
            assertEquals("two", found.getObject().get("v").asJsonString().getValue());
        } finally {
            processor.processMessage(new DropCollectionRequest(TestGlobals.DB, collName));
        }
    }

    // A single-document insert calls updatePageSizeInMemory synchronously; the subsequent background
    // CREATED event must only persist — entryCount and pageSize must both remain at 1x, not 2x.
    @Test
    public void test_single_save_insert_page_entry_count_single_counted() throws Exception {
        final var cache = IocContainer.get(Cache.class);

        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("singleSaveId1"));
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(obj);
        save.set_id("singleSaveId1");

        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());

        // Capture the page state set by the synchronous updatePageSizeInMemory call.
        final var pageEntries = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(pageEntries);
        final var page0Before = pageEntries.stream().filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0Before.isPresent());
        final long countBefore = page0Before.get().getEntryCount();
        final long sizeBefore = page0Before.get().getPageSize();

        // Simulate the background EntityEvent(CREATED) arriving.
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id("singleSaveId1");
        entry.setPage(0L);
        AdminOperationHelper.bulkUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, List.of(entry));

        final var page0After = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0After.isPresent());
        assertEquals(countBefore, page0After.get().getEntryCount(), "entryCount must not be incremented again");
        assertEquals(sizeBefore, page0After.get().getPageSize(), "pageSize must not be incremented again");
    }

    // Bulk-save inserts also call updatePageSizeInMemory synchronously per inserted entry;
    // the subsequent background BulkEntityEvent must not double-count any of them.
    @Test
    public void test_bulk_save_insert_page_entry_count_single_counted() throws Exception {
        final var cache = IocContainer.get(Cache.class);

        final var obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("bulkSaveId1"));
        final var obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("bulkSaveId2"));

        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(obj1, obj2));
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());

        final var pageEntries = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(pageEntries);
        final var page0Before = pageEntries.stream().filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0Before.isPresent());
        final long countBefore = page0Before.get().getEntryCount();
        final long sizeBefore = page0Before.get().getPageSize();

        // Simulate the background BulkEntityEvent(CREATED) arriving.
        final var e1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj1);
        e1.set_id("bulkSaveId1");
        e1.setPage(0L);
        final var e2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj2);
        e2.set_id("bulkSaveId2");
        e2.setPage(0L);
        AdminOperationHelper.bulkUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, List.of(e1, e2));

        final var page0After = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0After.isPresent());
        assertEquals(countBefore, page0After.get().getEntryCount(), "entryCount must not be incremented again");
        assertEquals(sizeBefore, page0After.get().getPageSize(), "pageSize must not be incremented again");
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

    // REINDEX: returns OK with empty list when no indexes exist on the collection
    @Test
    public void test_reindex_collection_with_no_indexes_returns_ok_empty_list() {
        ReindexRequest request = new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, null);
        ReindexResponse response = (ReindexResponse) processor.processMessage(request);
        // All indexes from prior tests have been dropped; if some still exist the response is still OK
        assertEquals(OperationStatus.OK, response.getStatus());
    }
}
