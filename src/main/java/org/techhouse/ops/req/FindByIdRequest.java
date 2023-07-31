package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class FindByIdRequest extends OperationRequest {
    private String _id;
    public FindByIdRequest(String databaseName, String collectionName) {
        super(OperationType.FIND_BY_ID, databaseName, collectionName);
    }
}
