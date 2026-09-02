package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DeleteScheduleResponse extends OperationResponse {
    public DeleteScheduleResponse(String message) {
        super(OperationType.DELETE_SCHEDULE, OperationStatus.OK, message);
    }
}
