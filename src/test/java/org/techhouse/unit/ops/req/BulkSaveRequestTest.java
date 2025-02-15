package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.BulkSaveRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class BulkSaveRequestTest {
    // Create BulkSaveRequest with valid database and collection names
    @Test
    public void create_bulk_save_request_with_valid_names() {
        String dbName = "testDb";
        String collectionName = "testCollection";
    
        BulkSaveRequest request = new BulkSaveRequest(dbName, collectionName);
    
        assertEquals(OperationType.BULK_SAVE, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertNull(request.getObjects());
    }

    // Create BulkSaveRequest with empty database name
    @Test
    public void create_bulk_save_request_with_empty_db_name() {
        String dbName = "";
        String collectionName = "testCollection";
    
        BulkSaveRequest request = new BulkSaveRequest(dbName, collectionName);
    
        assertEquals(OperationType.BULK_SAVE, request.getType());
        assertEquals("", request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertNull(request.getObjects());
    }

    // Add tests for getters and setters provided by lombok
    @Test
    public void test_bulk_save_request_getters_and_setters() {
        String dbName = "testDb";
        String collectionName = "testCollection";
        List<JsonObject> jsonObjects = List.of(new JsonObject(), new JsonObject());

        BulkSaveRequest request = new BulkSaveRequest(dbName, collectionName);
        request.setObjects(jsonObjects);

        assertEquals(OperationType.BULK_SAVE, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertEquals(jsonObjects, request.getObjects());
    }
}