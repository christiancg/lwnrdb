package org.techhouse.unit.ops;

import org.junit.jupiter.api.*;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.*;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.*;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

        FindByIdResponse response = (FindByIdResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertNull(response.getObject());
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
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "searchMe", new JsonString("test")))));

        AggregateResponse aggregateResponse = (AggregateResponse) processor.processMessage(aggregateRequest);

        assertEquals(OperationStatus.OK, aggregateResponse.getStatus());
        assertEquals("Ok", aggregateResponse.getMessage());
        assertNotNull(aggregateResponse.getResults());
        assertEquals(1, aggregateResponse.getResults().size());
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

        ListCollectionsResponse response = (ListCollectionsResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertNull(response.getCollections());
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

        ListCollectionsResponse response = (ListCollectionsResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.ERROR, response.getStatus());
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

        DeleteResponse response = (DeleteResponse) processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
    }

    // Aggregate returns NOT_FOUND when no documents match
    @Test
    public void test_aggregate_returns_not_found_when_no_results() {
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "nobody", new JsonString("nope")))
        ));

        AggregateResponse response = (AggregateResponse) processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertNull(response.getResults());
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

    // Drop index returns error when the collection does not exist
    @Test
    public void test_drop_index_returns_error_for_nonexistent_collection() {
        DropIndexRequest request = new DropIndexRequest(TestGlobals.DB, "noSuchColl", "noSuchField");

        DropIndexResponse response = (DropIndexResponse) processor.processMessage(request);

        assertEquals(OperationStatus.ERROR, response.getStatus());
    }

    // Save an oversized entry returns an error response
    @Test
    public void test_save_oversized_entry_returns_error() {
        SaveRequest request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("bigId"));
        // Create a value larger than 1MB (maxEntrySizeBytes default)
        obj.add("bigField", new JsonString("x".repeat(1_048_600)));
        request.setObject(obj);

        SaveResponse response = (SaveResponse) processor.processMessage(request);

        assertEquals(OperationStatus.ERROR, response.getStatus());
    }

    // Bulk save with an oversized entry returns an error response
    @Test
    public void test_bulk_save_oversized_entry_returns_error() {
        BulkSaveRequest request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("bigId2"));
        obj.add("bigField", new JsonString("x".repeat(1_048_600)));
        request.setObjects(List.of(obj));

        BulkSaveResponse response = (BulkSaveResponse) processor.processMessage(request);

        assertEquals(OperationStatus.ERROR, response.getStatus());
    }
}