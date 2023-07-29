package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class CreateDatabaseResponse extends OperationResponse {
    public CreateDatabaseResponse(OperationStatus status, String message) {
        super(OperationType.CREATE_DATABASE, status, message);
    }
}
