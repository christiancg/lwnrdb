package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DropCollectionResponse extends OperationResponse {
    public DropCollectionResponse(OperationStatus status, String message) {
        super(OperationType.DROP_COLLECTION, status, message);
    }
}
