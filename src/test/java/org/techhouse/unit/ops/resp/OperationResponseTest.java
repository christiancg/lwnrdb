package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
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

    // Three-arg constructor sets errorCode to null
    @Test
    public void create_operation_response_three_arg_has_null_error_code() {
        OperationResponse response = new OperationResponse(OperationType.SAVE, OperationStatus.OK, "ok");

        assertNull(response.getErrorCode());
    }

    // (type, ErrorCode) constructor derives status and message from ErrorCode
    @Test
    public void create_operation_response_error_code_constructor_sets_code_and_message() {
        OperationResponse response = new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ENTRY_NOT_FOUND);

        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), response.getErrorCode());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getDefaultMessage(), response.getMessage());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getStatus(), response.getStatus());
    }

    // (type, String, ErrorCode) constructor uses the provided message, status from ErrorCode
    @Test
    public void create_operation_response_custom_message_with_error_code() {
        OperationResponse response = new OperationResponse(OperationType.DELETE, "Entry with id abc123 not found",
                ErrorCode.ENTRY_NOT_FOUND);

        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), response.getErrorCode());
        assertEquals("Entry with id abc123 not found", response.getMessage());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getStatus(), response.getStatus());
    }

    // (type, ErrorCode, String detail) constructor appends detail to defaultMessage
    @Test
    public void create_operation_response_error_code_with_detail() {
        OperationResponse response = new OperationResponse(OperationType.DROP_INDEX, ErrorCode.INDEX_NOT_FOUND,
                "myField");

        assertEquals(ErrorCode.INDEX_NOT_FOUND.getCode(), response.getErrorCode());
        assertEquals(ErrorCode.INDEX_NOT_FOUND.getDefaultMessage() + ": myField", response.getMessage());
        assertEquals(ErrorCode.INDEX_NOT_FOUND.getStatus(), response.getStatus());
    }
}
