package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.ListDatabasesResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ListDatabasesResponseTest {
    // Response with OK status and database list
    @Test
    public void test_response_with_ok_status_and_database_list() {
        List<String> databases = List.of("db1", "db2");
        ListDatabasesResponse response = new ListDatabasesResponse(OperationStatus.OK, "Ok", databases);

        assertEquals(OperationType.LIST_DATABASES, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Ok", response.getMessage());
        assertEquals(databases, response.getDatabases());
    }

    // Response with ERROR status and null list
    @Test
    public void test_response_with_error_status_null_list() {
        ListDatabasesResponse response = new ListDatabasesResponse(OperationStatus.ERROR, "Error occurred", null);

        assertEquals(OperationType.LIST_DATABASES, response.getType());
        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("Error occurred", response.getMessage());
        assertNull(response.getDatabases());
    }

    // Response with OK status and empty database list
    @Test
    public void test_response_with_ok_status_and_empty_list() {
        List<String> emptyList = List.of();
        ListDatabasesResponse response = new ListDatabasesResponse(OperationStatus.OK, "Ok", emptyList);

        assertEquals(OperationType.LIST_DATABASES, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Ok", response.getMessage());
        assertEquals(emptyList, response.getDatabases());
    }
}
