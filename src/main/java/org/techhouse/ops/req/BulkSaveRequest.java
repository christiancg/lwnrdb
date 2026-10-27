package org.techhouse.ops.req;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

public class BulkSaveRequest extends OperationRequest {
    public List<JsonObject> objects;
    private transient boolean backgroundFollowOn;

    public BulkSaveRequest(String databaseName, String collectionName) {
        super(OperationType.BULK_SAVE, databaseName, collectionName);
    }

    public List<JsonObject> getObjects() {
        return objects;
    }

    public void setObjects(List<JsonObject> objects) {
        this.objects = objects;
    }

    public boolean isBackgroundFollowOn() {
        return backgroundFollowOn;
    }

    public void setBackgroundFollowOn(boolean backgroundFollowOn) {
        this.backgroundFollowOn = backgroundFollowOn;
    }
}
