package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class RollbackTransactionRequest extends OperationRequest {
    public RollbackTransactionRequest() {
        super(OperationType.ROLLBACK_TRANSACTION, null, null);
    }
}
