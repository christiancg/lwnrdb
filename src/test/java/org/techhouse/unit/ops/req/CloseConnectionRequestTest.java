package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CloseConnectionRequest;

import static org.junit.jupiter.api.Assertions.*;

public class CloseConnectionRequestTest {
    // Verify constructor creates instance with CLOSE_CONNECTION operation type
    @Test
    public void test_constructor_sets_close_connection_type() {
        CloseConnectionRequest request = new CloseConnectionRequest();

        assertEquals(OperationType.CLOSE_CONNECTION, request.getType());
        assertNull(request.getDatabaseName());
        assertNull(request.getCollectionName());
    }
}