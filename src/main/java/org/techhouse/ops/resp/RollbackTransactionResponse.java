package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class RollbackTransactionResponse extends OperationResponse {
    public RollbackTransactionResponse(String message) {
        super(OperationType.ROLLBACK_TRANSACTION, OperationStatus.OK, message);
    }
}
