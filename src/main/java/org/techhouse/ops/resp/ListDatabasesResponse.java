package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

@Getter
@Setter
public class ListDatabasesResponse extends OperationResponse {
    private final List<String> databases;

    public ListDatabasesResponse(OperationStatus status, String message, List<String> databases) {
        super(OperationType.LIST_DATABASES, status, message);
        this.databases = databases;
    }
}
