package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.techhouse.log.LogWriter;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;

public class OperationResponseTest {

    @Test
    public void create_operation_response_with_valid_parameters() {
        OperationResponse response = new OperationResponse(OperationType.SAVE, OperationStatus.OK,
                "Operation completed successfully");

        assertEquals(OperationType.SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Operation completed successfully", response.getMessage());
    }

    @Test
    public void create_operation_response_with_null_message() {
        OperationResponse response = new OperationResponse(OperationType.DELETE, OperationStatus.OK, null);

        assertEquals(OperationType.DELETE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getMessage());
    }

    @Test
    public void create_operation_response_three_arg_has_null_error_code() {
        OperationResponse response = new OperationResponse(OperationType.SAVE, OperationStatus.OK, "ok");

        assertNull(response.getErrorCode());
    }

    @Test
    public void create_operation_response_error_code_constructor_sets_code_and_message() {
        OperationResponse response = new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ENTRY_NOT_FOUND);

        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), response.getErrorCode());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getDefaultMessage(), response.getMessage());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getStatus(), response.getStatus());
    }

    @Test
    public void create_operation_response_custom_message_with_error_code() {
        OperationResponse response = new OperationResponse(OperationType.DELETE, "Entry with id abc123 not found",
                ErrorCode.ENTRY_NOT_FOUND);

        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), response.getErrorCode());
        assertEquals("Entry with id abc123 not found", response.getMessage());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getStatus(), response.getStatus());
    }

    @Test
    public void create_operation_response_error_code_with_detail() {
        OperationResponse response = new OperationResponse(OperationType.DROP_INDEX, ErrorCode.INDEX_NOT_FOUND,
                "myField");

        assertEquals(ErrorCode.INDEX_NOT_FOUND.getCode(), response.getErrorCode());
        assertEquals(ErrorCode.INDEX_NOT_FOUND.getDefaultMessage() + ": myField", response.getMessage());
        assertEquals(ErrorCode.INDEX_NOT_FOUND.getStatus(), response.getStatus());
    }
    @Test
    public void test_respondOrError_returns_what_the_attempt_produced() {
        final var response = OperationResponse.respondOrError(OperationType.SAVE, ErrorCode.ERROR_SAVING,
                () -> OperationResponse.ok(OperationType.SAVE, "Ok"));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getErrorCode());
    }

    // The failure is turned into an error code for the client, but it must not vanish: this is the one
    // choke point where the ~30 handlers that share this shape would otherwise swallow their cause.
    @Test
    public void test_respondOrError_logs_the_failure_it_swallows() {
        try (var logWriter = mockStatic(LogWriter.class)) {
            final var response = OperationResponse.respondOrError(OperationType.SAVE, ErrorCode.ERROR_SAVING, () -> {
                throw new IllegalStateException("disk gone");
            });

            assertEquals(ErrorCode.ERROR_SAVING.getCode(), response.getErrorCode());
            logWriter.verify(() -> LogWriter.writeLogEntry(
                    argThat(entry -> entry.contains("SAVE failed with " + ErrorCode.ERROR_SAVING.getCode())
                            && entry.contains("disk gone"))));
        }
    }
}
