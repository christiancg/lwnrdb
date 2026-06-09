package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

@Getter
@Setter
public class ListCollectionsResponse extends OperationResponse {
    private final List<String> collections;

    public ListCollectionsResponse(OperationStatus status, String message, List<String> collections) {
        super(OperationType.LIST_COLLECTIONS, status, message);
        this.collections = collections;
    }
}
