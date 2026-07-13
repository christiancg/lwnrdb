package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ListTransactionsRequest extends OperationRequest {
    public ListTransactionsRequest() {
        super(OperationType.LIST_TRANSACTIONS, null, null);
    }
}
