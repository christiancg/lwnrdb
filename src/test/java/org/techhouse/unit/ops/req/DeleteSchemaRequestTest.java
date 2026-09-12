package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DeleteSchemaRequest;

public class DeleteSchemaRequestTest {
    @Test
    public void test_constructor_and_getters() {
        final var request = new DeleteSchemaRequest("testDb", "testColl");
        assertEquals(OperationType.DELETE_SCHEMA, request.getType());
        assertEquals("testDb", request.getDatabaseName());
        assertEquals("testColl", request.getCollectionName());
    }
}
