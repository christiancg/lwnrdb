package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class CreateIndexResponse extends OperationResponse {
    public CreateIndexResponse(OperationStatus status, String message) {
        super(OperationType.CREATE_INDEX, status, message);
    }
}
