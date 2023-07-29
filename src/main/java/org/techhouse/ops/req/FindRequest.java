package org.techhouse.ops.req;

import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class FindRequest extends OperationRequest {
    public JsonObject query;
    public FindRequest(String databaseName, String collectionName) {
        super(OperationType.FIND, databaseName, collectionName);
    }
}
