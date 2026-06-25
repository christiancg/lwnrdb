package org.techhouse.ops.resp;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class FindByIdResponse extends OperationResponse {
    public JsonObject object;

    public FindByIdResponse(String message, JsonObject object) {
        super(OperationType.FIND_BY_ID, OperationStatus.OK, message);
        this.object = object;
    }

    public JsonObject getObject() {
        return object;
    }

    public void setObject(JsonObject object) {
        this.object = object;
    }
}
