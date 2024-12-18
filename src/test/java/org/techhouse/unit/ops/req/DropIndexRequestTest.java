package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DropIndexRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DropIndexRequestTest {
    // Create DropIndexRequest with valid database name, collection name and field name
    @Test
    public void test_create_drop_index_request_with_valid_params() {
        String dbName = "testDb";
        String collectionName = "testCollection"; 
        String fieldName = "testField";

        DropIndexRequest request = new DropIndexRequest(dbName, collectionName, fieldName);

        assertEquals(OperationType.DROP_INDEX, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertEquals(fieldName, request.getFieldName());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        String dbName = "testDb";
        String collectionName = "testCollection";
        String fieldName = "testField";

        DropIndexRequest request = new DropIndexRequest(dbName, collectionName, fieldName);

        // Test getters
        assertEquals(OperationType.DROP_INDEX, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertEquals(fieldName, request.getFieldName());

        // Test setters
        String newFieldName = "newTestField";
        request.setFieldName(newFieldName);
        assertEquals(newFieldName, request.getFieldName());
    }
}