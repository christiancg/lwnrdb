package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ListDatabasesRequest extends OperationRequest {
    public ListDatabasesRequest() {
        super(OperationType.LIST_DATABASES, null, null);
    }
}
