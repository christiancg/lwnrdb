package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class CreateIndexRequest extends OperationRequest {
    private String fieldName;

    public CreateIndexRequest(String databaseName, String collectionName, String fieldName) {
        super(OperationType.CREATE_INDEX, databaseName, collectionName);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }
}
