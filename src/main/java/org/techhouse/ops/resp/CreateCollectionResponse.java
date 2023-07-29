package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class CreateCollectionResponse extends OperationResponse {
    public CreateCollectionResponse(OperationStatus status, String message) {
        super(OperationType.CREATE_COLLECTION, status, message);
    }
}
