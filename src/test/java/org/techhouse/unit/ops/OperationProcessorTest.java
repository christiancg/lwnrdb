package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.SaveRequest;
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
}