package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class ListCollectionsRequest extends OperationRequest {
    public ListCollectionsRequest(String databaseName) {
        super(OperationType.LIST_COLLECTIONS, databaseName, null);
    }
}
