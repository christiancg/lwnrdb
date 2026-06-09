package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class ListDatabasesRequest extends OperationRequest {
    public ListDatabasesRequest() {
        super(OperationType.LIST_DATABASES, null, null);
    }
}
