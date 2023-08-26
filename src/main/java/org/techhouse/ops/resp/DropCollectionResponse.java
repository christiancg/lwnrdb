package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class DropCollectionResponse extends OperationResponse {
    public DropCollectionResponse(OperationStatus status, String message) {
        super(OperationType.DROP_COLLECTION, status, message);
    }
}
