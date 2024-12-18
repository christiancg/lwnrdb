package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DeleteRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DeleteRequestTest {
    // Constructor correctly sets DELETE operation type
    @Test
    public void test_constructor_sets_delete_operation_type() {
        DeleteRequest deleteRequest = new DeleteRequest("testDb", "testCollection");

        assertEquals(OperationType.DELETE, deleteRequest.getType());
        assertEquals("testDb", deleteRequest.getDatabaseName());
        assertEquals("testCollection", deleteRequest.getCollectionName());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        DeleteRequest deleteRequest = new DeleteRequest("testDb", "testCollection");
        deleteRequest.set_id("12345");

        assertEquals("12345", deleteRequest.get_id());
        assertEquals(OperationType.DELETE, deleteRequest.getType());
        assertEquals("testDb", deleteRequest.getDatabaseName());
        assertEquals("testCollection", deleteRequest.getCollectionName());
    }
}