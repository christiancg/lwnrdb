package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ListCollectionsResponse extends OperationResponse {
    private final List<String> collections;

    public ListCollectionsResponse(String message, List<String> collections) {
        super(OperationType.LIST_COLLECTIONS, OperationStatus.OK, message);
        this.collections = collections;
    }

    public List<String> getCollections() {
        return collections;
    }
}
