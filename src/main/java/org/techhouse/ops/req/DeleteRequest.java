package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class DeleteRequest extends OperationRequest {
    public String _id;
    public DeleteRequest(String databaseName, String collectionName) {
        super(OperationType.DELETE, databaseName, collectionName);
    }
}
