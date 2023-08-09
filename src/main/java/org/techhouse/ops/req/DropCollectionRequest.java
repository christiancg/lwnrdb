package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class DropCollectionRequest extends OperationRequest {
    public DropCollectionRequest(String databaseName, String collectionName) {
        super(OperationType.DROP_COLLECTION, databaseName, collectionName);
    }
}
