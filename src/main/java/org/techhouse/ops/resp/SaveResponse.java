package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class SaveResponse extends OperationResponse {
    public String _id;

    public SaveResponse(OperationStatus status, String message, String _id) {
        super(OperationType.SAVE, status, message);
        this._id = _id;
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }
}
