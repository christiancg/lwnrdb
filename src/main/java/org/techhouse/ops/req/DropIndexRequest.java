package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class DropIndexRequest extends OperationRequest {
    private String fieldName;

    public DropIndexRequest(String databaseName, String collectionName, String fieldName) {
        super(OperationType.DROP_INDEX, databaseName, collectionName);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }
}
