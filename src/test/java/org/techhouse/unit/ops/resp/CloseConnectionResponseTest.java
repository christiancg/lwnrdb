package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.CloseConnectionResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CloseConnectionResponseTest {
    // Constructor creates instance with CLOSE_CONNECTION type
    @Test
    public void test_constructor_creates_instance_with_close_connection_type() {
        CloseConnectionResponse response = new CloseConnectionResponse();

        assertEquals(OperationType.CLOSE_CONNECTION, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(Globals.CLOSE_CONNECTION_MESSAGE, response.getMessage());
    }
}