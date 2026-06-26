package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DropCollectionResponse extends OperationResponse {
    public DropCollectionResponse(String message) {
        super(OperationType.DROP_COLLECTION, OperationStatus.OK, message);
    }
}
