package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class FindByIdRequest extends OperationRequest {
    private String _id;

    public FindByIdRequest(String databaseName, String collectionName) {
        super(OperationType.FIND_BY_ID, databaseName, collectionName);
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }
}
