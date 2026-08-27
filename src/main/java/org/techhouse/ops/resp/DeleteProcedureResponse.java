package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DeleteProcedureResponse extends OperationResponse {
    public DeleteProcedureResponse(String message) {
        super(OperationType.DELETE_PROCEDURE, OperationStatus.OK, message);
    }
}
