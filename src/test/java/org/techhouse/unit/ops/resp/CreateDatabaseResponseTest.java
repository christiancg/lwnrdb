package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.CreateDatabaseResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CreateDatabaseResponseTest {
    // Create database response with OK status and success message
    @Test
    public void test_create_database_response_with_ok_status() {
        String successMessage = "Database created successfully";
        CreateDatabaseResponse response = new CreateDatabaseResponse(OperationStatus.OK, successMessage);

        assertEquals(OperationType.CREATE_DATABASE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(successMessage, response.getMessage());
    }

    // Create response with null status
    @Test
    public void test_create_database_response_with_null_status() {
        String message = "Test message";
        CreateDatabaseResponse response = new CreateDatabaseResponse(null, message);

        assertEquals(OperationType.CREATE_DATABASE, response.getType());
        assertNull(response.getStatus());
        assertEquals(message, response.getMessage());
    }
}