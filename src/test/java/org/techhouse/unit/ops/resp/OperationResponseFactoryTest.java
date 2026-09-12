package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;

public class OperationResponseFactoryTest {
    private static final List<OperationType> COLLAPSED_TYPES = List.of(OperationType.AUTHENTICATE,
            OperationType.CHANGE_PERMISSIONS, OperationType.COMMIT_TRANSACTION, OperationType.CREATE_COLLECTION,
            OperationType.CREATE_DATABASE, OperationType.CREATE_INDEX, OperationType.CREATE_USER,
            OperationType.DELETE_PROCEDURE, OperationType.DELETE_SCHEDULE, OperationType.DELETE_SCHEMA,
            OperationType.DELETE_TRIGGER, OperationType.DELETE_USER, OperationType.DROP_COLLECTION,
            OperationType.DROP_DATABASE, OperationType.DROP_INDEX, OperationType.RESOLVE_TRANSACTION,
            OperationType.ROLLBACK_TRANSACTION, OperationType.SET_DATABASE_OWNERS, OperationType.SET_PASSWORD);

    @Test
    public void test_ok_carries_type_ok_status_and_message() {
        for (final var type : COLLAPSED_TYPES) {
            final var response = OperationResponse.ok(type, "done");
            assertEquals(type, response.getType());
            assertEquals(OperationStatus.OK, response.getStatus());
            assertEquals("done", response.getMessage());
            assertNull(response.getErrorCode());
        }
    }

    @Test
    public void test_ok_accepts_null_message() {
        final var response = OperationResponse.ok(OperationType.CREATE_DATABASE, null);
        assertEquals(OperationType.CREATE_DATABASE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getMessage());
    }
}
