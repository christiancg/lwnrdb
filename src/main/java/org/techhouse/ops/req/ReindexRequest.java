package org.techhouse.ops.req;

import java.util.List;
import org.techhouse.ops.OperationType;

public class ReindexRequest extends OperationRequest {
    private List<String> fieldNames;
    private boolean primaryKey;

    public ReindexRequest() {
        super(OperationType.REINDEX, null, null);
    }

    public ReindexRequest(String databaseName, String collectionName, List<String> fieldNames) {
        this(databaseName, collectionName, fieldNames, false);
    }

    public ReindexRequest(String databaseName, String collectionName, List<String> fieldNames, boolean primaryKey) {
        super(OperationType.REINDEX, databaseName, collectionName);
        this.fieldNames = fieldNames;
        this.primaryKey = primaryKey;
    }

    public List<String> getFieldNames() {
        return fieldNames != null ? fieldNames : List.of();
    }

    public void setFieldNames(List<String> fieldNames) {
        this.fieldNames = fieldNames;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
    }
}
