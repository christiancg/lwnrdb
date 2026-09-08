package org.techhouse.ops.req;

import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

public class ChangePermissionsRequest extends PermissionsRequest {

    public ChangePermissionsRequest() {
        super(OperationType.CHANGE_PERMISSIONS, null, null);
        this.admin = false;
        this.globalPermissions = new JsonArray();
        this.databasePermissions = new JsonObject();
        this.collectionPermissions = new JsonObject();
        this.scriptPermissions = new JsonObject();
    }

}
