package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class DeleteUserRequest extends OperationRequest {
    private String username;

    public DeleteUserRequest() {
        super(OperationType.DELETE_USER, null, null);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
