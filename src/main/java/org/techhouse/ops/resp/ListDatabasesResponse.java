package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ListDatabasesResponse extends OperationResponse {
    private final List<String> databases;

    public ListDatabasesResponse(OperationStatus status, String message, List<String> databases) {
        super(OperationType.LIST_DATABASES, status, message);
        this.databases = databases;
    }

    public List<String> getDatabases() {
        return databases;
    }
}
