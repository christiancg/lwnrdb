package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class OperationResponse {
    private final OperationType type;
    private final OperationStatus status;
    private final String message;

    public OperationResponse(OperationType type, OperationStatus status, String message) {
        this.type = type;
        this.status = status;
        this.message = message;
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
}
