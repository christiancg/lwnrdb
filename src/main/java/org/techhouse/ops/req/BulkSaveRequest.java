package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

import java.util.List;

@Getter
@Setter
public class BulkSaveRequest extends OperationRequest {
    public List<JsonObject> objects;
    public BulkSaveRequest(String databaseName, String collectionName) {
        super(OperationType.BULK_SAVE, databaseName, collectionName);
    }
}
