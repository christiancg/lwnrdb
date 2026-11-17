package org.techhouse.ops.req;

import java.util.LinkedHashSet;
import java.util.List;
import org.techhouse.ops.OperationType;

public class CreateIndexRequest extends OperationRequest {
    private String fieldName;
    private List<String> fieldNames;

    public CreateIndexRequest(String databaseName, String collectionName, String fieldName) {
        super(OperationType.CREATE_INDEX, databaseName, collectionName);
        this.fieldName = fieldName;
    }

    public static CreateIndexRequest ofFields(String databaseName, String collectionName, List<String> fieldNames) {
        final var request = new CreateIndexRequest(databaseName, collectionName, null);
        request.setFieldNames(fieldNames);
        return request;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public List<String> getFieldNames() {
        return fieldNames;
    }

    public void setFieldNames(List<String> fieldNames) {
        this.fieldNames = fieldNames;
    }

    public List<String> targetFieldNames() {
        final var targets = new LinkedHashSet<String>();
        if (fieldName != null) {
            targets.add(fieldName);
        }
        if (fieldNames != null) {
            targets.addAll(fieldNames);
        }
        return List.copyOf(targets);
    }
}
