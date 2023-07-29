package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class CreateCollectionRequest extends OperationRequest {
    public CreateCollectionRequest(String databaseName, String collectionName) {
        super(OperationType.CREATE_COLLECTION, databaseName, collectionName);
    }
}
