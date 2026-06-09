package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

public class ListCollectionsResponse extends OperationResponse {
    private final List<String> collections;

    public ListCollectionsResponse(OperationStatus status, String message, List<String> collections) {
        super(OperationType.LIST_COLLECTIONS, status, message);
        this.collections = collections;
    }

    public List<String> getCollections() {
        return collections;
    }
}
