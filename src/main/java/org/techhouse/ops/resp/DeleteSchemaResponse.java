package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DeleteSchemaResponse extends OperationResponse {
    public DeleteSchemaResponse(String message) {
        super(OperationType.DELETE_SCHEMA, OperationStatus.OK, message);
    }
}
