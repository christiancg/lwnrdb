package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateIndexRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CreateIndexRequestTest {
    // Constructor correctly sets OperationType.CREATE_INDEX as type
    @Test
    public void test_constructor_sets_create_index_operation_type() {
        String dbName = "testDb";
        String collName = "testColl";
        String fieldName = "testField";
    
        CreateIndexRequest request = new CreateIndexRequest(dbName, collName, fieldName);
    
        assertEquals(OperationType.CREATE_INDEX, request.getType());
    }

    // add tests for getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        String dbName = "testDb";
        String collName = "testColl";
        String fieldName = "testField";

        CreateIndexRequest request = new CreateIndexRequest(dbName, collName, fieldName);

        // Test getter methods
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
        assertEquals(fieldName, request.getFieldName());

        // Test setter method
        String newFieldName = "newTestField";
        request.setFieldName(newFieldName);
        assertEquals(newFieldName, request.getFieldName());
    }
}