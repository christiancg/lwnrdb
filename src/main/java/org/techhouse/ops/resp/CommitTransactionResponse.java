package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class CommitTransactionResponse extends OperationResponse {
    public CommitTransactionResponse(String message) {
        super(OperationType.COMMIT_TRANSACTION, OperationStatus.OK, message);
    }
}
