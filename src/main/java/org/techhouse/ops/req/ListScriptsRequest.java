package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ListScriptsRequest extends OperationRequest {
    public ListScriptsRequest() {
        super(OperationType.LIST_SCRIPTS, null, null);
    }
}
