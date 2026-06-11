package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class AuthenticateRequest extends OperationRequest {
    private String username;
    private String password;

    public AuthenticateRequest() {
        super(OperationType.AUTHENTICATE, null, null);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
