package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class DropDatabaseRequest extends OperationRequest {
    public DropDatabaseRequest(String databaseName) {
        super(OperationType.DROP_DATABASE, databaseName, null);
    }
}
