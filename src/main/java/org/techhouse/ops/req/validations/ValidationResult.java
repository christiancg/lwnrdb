package org.techhouse.ops.req.validations;

import org.techhouse.ops.ErrorCode;

public final class ValidationResult {
    private final boolean valid;
    private final String errorMessage;
    private final ErrorCode errorCode;

    private ValidationResult(boolean valid, String errorMessage, ErrorCode errorCode) {
        this.valid = valid;
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, null, null);
    }

    public static ValidationResult fail(String errorMessage) {
        return new ValidationResult(false, errorMessage, ErrorCode.VALIDATION_ERROR);
    }

    // A rejection the caller already has a dedicated code for, so the client sees the same code the
    // equivalent RUN_SCRIPT rejection would have returned rather than a generic validation error.
    public static ValidationResult fail(ErrorCode errorCode, String errorMessage) {
        return new ValidationResult(false, errorMessage, errorCode);
    }

    public boolean isValid() {
        return valid;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
