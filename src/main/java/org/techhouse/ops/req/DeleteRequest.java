package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class DeleteRequest extends OperationRequest {
    public String _id;

    public DeleteRequest(String databaseName, String collectionName) {
        super(OperationType.DELETE, databaseName, collectionName);
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }
}
