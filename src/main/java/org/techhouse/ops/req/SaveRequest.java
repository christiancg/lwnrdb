package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class SaveRequest extends OperationRequest {
    public String _id;
    public JsonObject object;
    public SaveRequest(String databaseName, String collectionName) {
        super(OperationType.SAVE, databaseName, collectionName);
    }
}
