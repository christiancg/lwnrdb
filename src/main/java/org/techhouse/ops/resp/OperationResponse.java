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

    public OperationResponse(OperationType type, OperationStatus status, String message) {
        this(type, status, message, null);
    }

    public OperationResponse(OperationType type, ErrorCode errorCode) {
        this(type, errorCode.getStatus(), errorCode.getDefaultMessage(), errorCode.getCode());
    }

    public OperationResponse(OperationType type, String message, ErrorCode errorCode) {
        this(type, errorCode.getStatus(), message, errorCode.getCode());
    }

    public OperationResponse(OperationType type, ErrorCode errorCode, String detail) {
        this(type, errorCode.getStatus(), errorCode.getDefaultMessage() + ": " + detail, errorCode.getCode());
    }

    public static OperationResponse ok(OperationType type, String message) {
        return new OperationResponse(type, OperationStatus.OK, message);
    }

    public interface Attempt {
        OperationResponse run() throws Exception;
    }

    public static OperationResponse respondOrError(OperationType type, ErrorCode errorCode, Attempt attempt) {
        try {
            return attempt.run();
        } catch (Exception e) {
            ResponseLog.LOGGER.error(type + " failed with " + errorCode.getCode(), e);
            return new OperationResponse(type, errorCode);
        }
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
