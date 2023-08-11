package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class CreateIndexRequest extends OperationRequest {
    private String fieldName;
    public CreateIndexRequest(String databaseName, String collectionName, String fieldName) {
        super(OperationType.CREATE_INDEX, databaseName, collectionName);
        this.fieldName = fieldName;
    }
}
