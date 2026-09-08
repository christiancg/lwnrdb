package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ListTriggerRunsResponse extends OperationResponse {
    private final List<JsonObject> runs;

    public ListTriggerRunsResponse(String message, List<JsonObject> runs) {
        super(OperationType.LIST_TRIGGER_RUNS, OperationStatus.OK, message);
        this.runs = runs;
    }

    public List<JsonObject> getRuns() {
        return runs;
    }
}
