package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OperationProcessorTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws InterruptedException, IOException, NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Process different operation types and return appropriate response objects
    @Test
    public void test_process_message_returns_correct_response_type() {
        OperationProcessor processor = new OperationProcessor();

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
        OperationProcessor processor = new OperationProcessor();

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
        OperationProcessor processor = new OperationProcessor();

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
        OperationProcessor processor = new OperationProcessor();

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
        OperationProcessor processor = new OperationProcessor();

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
        OperationProcessor processor = new OperationProcessor();

        CreateDatabaseRequest request = new CreateDatabaseRequest("testDb");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationType.CREATE_DATABASE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertInstanceOf(CreateDatabaseResponse.class, response);
    }

    // Process different operation types and return appropriate response objects
    @Test
    public void test_drop_database() {
        OperationProcessor processor = new OperationProcessor();

        CreateDatabaseRequest request = new CreateDatabaseRequest("testDb");

        OperationResponse response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());

        DropDatabaseRequest request2 = new DropDatabaseRequest("testDb");
        OperationResponse response2 = processor.processMessage(request2);
        assertEquals(OperationStatus.OK, response2.getStatus());
        assertEquals(OperationType.DROP_DATABASE, response2.getType());
        assertInstanceOf(DropDatabaseResponse.class, response2);
    }

    // Create and drop indexes with proper validation
    @Test
    public void test_create_and_drop_index() {
        OperationProcessor processor = new OperationProcessor();

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
        OperationProcessor processor = new OperationProcessor();

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
        OperationProcessor processor = new OperationProcessor();

        // Create Collection
        CreateCollectionRequest createRequest = new CreateCollectionRequest(TestGlobals.DB, "testColl");
        OperationResponse createResponse = processor.processMessage(createRequest);

        assertNotNull(createResponse);
        assertInstanceOf(CreateCollectionResponse.class, createResponse);
        assertEquals(OperationStatus.OK, createResponse.getStatus());

        // Drop Collection
        DropCollectionRequest dropRequest = new DropCollectionRequest(TestGlobals.DB, "testColl");
        OperationResponse dropResponse = processor.processMessage(dropRequest);

        assertNotNull(dropResponse);
        assertInstanceOf(DropCollectionResponse.class, dropResponse);
        assertEquals(OperationStatus.OK, dropResponse.getStatus());
    }
}