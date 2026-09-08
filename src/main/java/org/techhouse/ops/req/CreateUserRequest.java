package org.techhouse.ops.req;

import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

public class CreateUserRequest extends PermissionsRequest {
    private String password;

    public CreateUserRequest() {
        super(OperationType.CREATE_USER, null, null);
        this.admin = false;
        this.globalPermissions = new JsonArray();
        this.databasePermissions = new JsonObject();
        this.collectionPermissions = new JsonObject();
        this.scriptPermissions = new JsonObject();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

}
