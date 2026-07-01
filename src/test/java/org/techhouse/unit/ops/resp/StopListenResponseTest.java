package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.StopListenResponse;

public class StopListenResponseTest {

    @Test
    public void constructor_setsTypeAndStatus() {
        final var resp = new StopListenResponse();

        assertEquals(OperationType.STOP_LISTEN, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertNotNull(resp.getMessage());
    }
}
