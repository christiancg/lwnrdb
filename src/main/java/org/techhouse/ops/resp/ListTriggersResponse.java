package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ListTriggersResponse extends OperationResponse {
    private final List<JsonObject> triggers;

    public ListTriggersResponse(String message, List<JsonObject> triggers) {
        super(OperationType.LIST_TRIGGERS, OperationStatus.OK, message);
        this.triggers = triggers;
    }

    public List<JsonObject> getTriggers() {
        return triggers;
    }
}
