package org.techhouse.ops.resp;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

public class ListUsersResponse extends OperationResponse {
    private final List<JsonObject> users;

    public ListUsersResponse(OperationStatus status, String message, List<JsonObject> users) {
        super(OperationType.LIST_USERS, status, message);
        this.users = users;
    }

    public List<JsonObject> getUsers() {
        return users;
    }
}
