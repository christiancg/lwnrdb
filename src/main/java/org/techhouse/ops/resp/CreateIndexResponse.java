package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class CreateIndexResponse extends OperationResponse {
    public CreateIndexResponse(OperationStatus status, String message) {
        super(OperationType.CREATE_INDEX, status, message);
    }
}
