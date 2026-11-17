package org.techhouse.ex;

import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;

public class InvalidCommandException extends RuntimeException {
    private final OperationType requestType;

    public InvalidCommandException(Exception exception) {
        this(null, exception);
    }

    public InvalidCommandException(OperationType requestType, Exception exception) {
        super(ErrorCode.INVALID_COMMAND.getDefaultMessage(), exception);
        this.requestType = requestType;
    }

    public OperationType getRequestType() {
        return requestType;
    }
}
