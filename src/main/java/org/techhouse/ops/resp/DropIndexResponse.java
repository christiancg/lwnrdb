package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DropIndexResponse extends OperationResponse {
    public DropIndexResponse(OperationStatus status, String message) {
        super(OperationType.DROP_INDEX, status, message);
    }
}
