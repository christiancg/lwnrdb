package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class DeleteResponse extends OperationResponse {
    public DeleteResponse(OperationStatus status, String message) {
        super(OperationType.DELETE, status, message);
    }
}
