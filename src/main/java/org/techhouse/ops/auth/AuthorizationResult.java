package org.techhouse.ops.auth;

import org.techhouse.ops.ErrorCode;

public final class AuthorizationResult {
    private static final AuthorizationResult ALLOW = new AuthorizationResult(true, null, null);
    private final boolean allowed;
    private final String reason;
    private final ErrorCode errorCode;

    private AuthorizationResult(boolean allowed, String reason, ErrorCode errorCode) {
        this.allowed = allowed;
        this.reason = reason;
        this.errorCode = errorCode;
    }

    public static AuthorizationResult allow() {
        return ALLOW;
    }

    public static AuthorizationResult deny(String reason) {
        return deny(reason, ErrorCode.NO_PERMISSIONS);
    }

    public static AuthorizationResult deny(String reason, ErrorCode errorCode) {
        return new AuthorizationResult(false, reason, errorCode);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
