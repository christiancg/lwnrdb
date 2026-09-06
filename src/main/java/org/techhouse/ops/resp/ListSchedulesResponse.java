package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ListSchedulesResponse extends OperationResponse {
    private final List<JsonObject> schedules;

    public ListSchedulesResponse(String message, List<JsonObject> schedules) {
        super(OperationType.LIST_SCHEDULES, OperationStatus.OK, message);
        this.schedules = schedules;
    }

    public List<JsonObject> getSchedules() {
        return schedules;
    }
}
