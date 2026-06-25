package org.techhouse.ops.req;

import java.util.List;
import org.techhouse.ops.OperationType;

public class ReindexRequest extends OperationRequest {
    private List<String> fieldNames;

    public ReindexRequest() {
        super(OperationType.REINDEX, null, null);
    }

    public ReindexRequest(String databaseName, String collectionName, List<String> fieldNames) {
        super(OperationType.REINDEX, databaseName, collectionName);
        this.fieldNames = fieldNames;
    }

    public List<String> getFieldNames() {
        return fieldNames != null ? fieldNames : List.of();
    }

    public void setFieldNames(List<String> fieldNames) {
        this.fieldNames = fieldNames;
    }
}
