package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.DropDatabaseResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DropDatabaseResponseTest {
    // Create DropDatabaseResponse with OK status and success message
    @Test
    public void test_create_drop_database_response_with_ok_status() {
        String successMessage = "Database dropped successfully";
    
        DropDatabaseResponse response = new DropDatabaseResponse(OperationStatus.OK, successMessage);
    
        assertEquals(OperationType.DROP_DATABASE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(successMessage, response.getMessage());
    }

    // Create DropDatabaseResponse with null status
    @Test
    public void test_create_drop_database_response_with_null_status() {
        String message = "Some message";
    
        DropDatabaseResponse response = new DropDatabaseResponse(null, message);
    
        assertEquals(OperationType.DROP_DATABASE, response.getType());
        assertNull(response.getStatus());
        assertEquals(message, response.getMessage());
    }
}