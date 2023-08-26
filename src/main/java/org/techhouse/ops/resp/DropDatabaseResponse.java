package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class DropDatabaseResponse extends OperationResponse {
    public DropDatabaseResponse(OperationStatus status, String message) {
        super(OperationType.DROP_DATABASE, status, message);
    }
}
