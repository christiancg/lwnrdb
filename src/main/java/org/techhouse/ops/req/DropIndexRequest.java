package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class DropIndexRequest extends OperationRequest {
    private String fieldName;
    public DropIndexRequest(String databaseName, String collectionName, String fieldName) {
        super(OperationType.DROP_INDEX, databaseName, collectionName);
        this.fieldName = fieldName;
    }
}
