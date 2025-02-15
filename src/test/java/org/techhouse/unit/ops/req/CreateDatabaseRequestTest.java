package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateDatabaseRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CreateDatabaseRequestTest {
    // Constructor creates object with CREATE_DATABASE operation type
    @Test
    public void test_constructor_sets_create_database_operation_type() {
        String dbName = "testDb";
        CreateDatabaseRequest request = new CreateDatabaseRequest(dbName);
    
        assertEquals(OperationType.CREATE_DATABASE, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertNull(request.getCollectionName());
    }

    // Handle empty string database name
    @Test
    public void test_constructor_accepts_empty_database_name() {
        String emptyDbName = "";
        CreateDatabaseRequest request = new CreateDatabaseRequest(emptyDbName);
    
        assertEquals(OperationType.CREATE_DATABASE, request.getType());
        assertEquals(emptyDbName, request.getDatabaseName());
        assertNull(request.getCollectionName());
    }
}