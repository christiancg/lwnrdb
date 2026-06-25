package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DropIndexResponse extends OperationResponse {
    public DropIndexResponse(String message) {
        super(OperationType.DROP_INDEX, OperationStatus.OK, message);
    }
}
