package org.techhouse.ops;

public class AggregateLimitException extends RuntimeException {
    private final transient ErrorCode errorCode;

    public AggregateLimitException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage(), null, false, false);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
