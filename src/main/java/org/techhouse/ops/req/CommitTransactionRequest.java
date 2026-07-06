package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class CommitTransactionRequest extends OperationRequest {
    public CommitTransactionRequest() {
        super(OperationType.COMMIT_TRANSACTION, null, null);
    }
}
