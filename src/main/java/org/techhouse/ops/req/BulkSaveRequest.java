package org.techhouse.ops.req;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

import java.util.List;

public class BulkSaveRequest extends OperationRequest {
    public List<JsonObject> objects;

    public BulkSaveRequest(String databaseName, String collectionName) {
        super(OperationType.BULK_SAVE, databaseName, collectionName);
    }

    public List<JsonObject> getObjects() {
        return objects;
    }

    public void setObjects(List<JsonObject> objects) {
        this.objects = objects;
    }
}
