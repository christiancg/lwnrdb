package org.techhouse.ops.resp;

import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class OperationResponse {
    private final OperationType type;
    private final OperationStatus status;
    private final String message;
    private final String errorCode;

    private OperationResponse(OperationType type, OperationStatus status, String message, String errorCode) {
        this.type = type;
        this.status = status;
        this.message = message;
        this.errorCode = errorCode;
    }

    // Success / no-error-code responses (status supplied by caller)
    public OperationResponse(OperationType type, OperationStatus status, String message) {
        this(type, status, message, null);
    }

    // Error: status and default message come from the ErrorCode
    public OperationResponse(OperationType type, ErrorCode errorCode) {
        this(type, errorCode.getStatus(), errorCode.getDefaultMessage(), errorCode.getCode());
    }

    // Error: status from ErrorCode, custom message supplied by caller
    public OperationResponse(OperationType type, String message, ErrorCode errorCode) {
        this(type, errorCode.getStatus(), message, errorCode.getCode());
    }

    // Error: status and default message from ErrorCode, with an appended detail
    public OperationResponse(OperationType type, ErrorCode errorCode, String detail) {
        this(type, errorCode.getStatus(), errorCode.getDefaultMessage() + ": " + detail, errorCode.getCode());
    }

    public OperationType getType() {
        return type;
    }

    public OperationStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
