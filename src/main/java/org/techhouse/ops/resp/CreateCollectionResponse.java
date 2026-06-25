package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class CreateCollectionResponse extends OperationResponse {
    public CreateCollectionResponse(String message) {
        super(OperationType.CREATE_COLLECTION, OperationStatus.OK, message);
    }
}
