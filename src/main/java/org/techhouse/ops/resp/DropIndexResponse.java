package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class DropIndexResponse extends OperationResponse {
    public DropIndexResponse(OperationStatus status, String message) {
        super(OperationType.DROP_INDEX, status, message);
    }
}
