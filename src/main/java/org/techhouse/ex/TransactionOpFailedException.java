package org.techhouse.ex;

public class TransactionOpFailedException extends RuntimeException {
    private final String errorCode;

    public TransactionOpFailedException(String opType, String errorCode, String detail) {
        super("A buffered " + opType + " operation failed while committing: " + detail);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
