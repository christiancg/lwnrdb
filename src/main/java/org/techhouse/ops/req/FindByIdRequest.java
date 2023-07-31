package org.techhouse.ops.req;

import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class FindByIdRequest extends OperationRequest {
    public JsonObject query;
    public FindByIdRequest(String databaseName, String collectionName) {
        super(OperationType.FIND_BY_ID, databaseName, collectionName);
    }
}
