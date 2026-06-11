package org.techhouse.ops.auth;

public class AuthorizationResult {
    private final boolean allowed;
    private final String reason;

    private AuthorizationResult(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    public static AuthorizationResult allow() {
        return new AuthorizationResult(true, null);
    }

    public static AuthorizationResult deny(String reason) {
        return new AuthorizationResult(false, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }
}
