package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ListProceduresRequest extends OperationRequest {
    private boolean includeSource;

    public ListProceduresRequest() {
        super(OperationType.LIST_PROCEDURES, null, null);
    }

    public ListProceduresRequest(String databaseName) {
        super(OperationType.LIST_PROCEDURES, databaseName, null);
    }

    public boolean isIncludeSource() {
        return includeSource;
    }

    public void setIncludeSource(boolean includeSource) {
        this.includeSource = includeSource;
    }
}
