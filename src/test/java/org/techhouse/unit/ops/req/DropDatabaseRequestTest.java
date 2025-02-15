package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DropDatabaseRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DropDatabaseRequestTest {
    // Create DropDatabaseRequest with valid database name
    @Test
    public void test_create_drop_database_request_with_valid_name() {
        String databaseName = "test_db";
    
        DropDatabaseRequest request = new DropDatabaseRequest(databaseName);
    
        assertEquals(OperationType.DROP_DATABASE, request.getType());
        assertEquals(databaseName, request.getDatabaseName());
        assertNull(request.getCollectionName());
    }

    // Create DropDatabaseRequest with empty database name
    @Test
    public void test_create_drop_database_request_with_empty_name() {
        String databaseName = "";
    
        DropDatabaseRequest request = new DropDatabaseRequest(databaseName);
    
        assertEquals(OperationType.DROP_DATABASE, request.getType());
        assertEquals(databaseName, request.getDatabaseName());
        assertNull(request.getCollectionName());
    }
}