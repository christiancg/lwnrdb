package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ejson.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class FindByIdResponse extends OperationResponse {
    public JsonObject object;
    public FindByIdResponse(OperationStatus status, String message, JsonObject object) {
        super(OperationType.FIND_BY_ID, status, message);
        this.object = object;
    }
}
