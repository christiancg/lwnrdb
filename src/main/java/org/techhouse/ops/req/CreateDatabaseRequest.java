package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class CreateDatabaseRequest extends OperationRequest {
    public CreateDatabaseRequest(String databaseName, String collectionName) {
        super(OperationType.CREATE_DATABASE, databaseName, collectionName);
    }
}
