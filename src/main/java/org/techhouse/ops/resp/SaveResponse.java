package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class SaveResponse extends OperationResponse {
    public String _id;
    public boolean inserted;

    public SaveResponse(String message, String _id) {
        this(message, _id, false);
    }

    public SaveResponse(String message, String _id, boolean inserted) {
        super(OperationType.SAVE, OperationStatus.OK, message);
        this._id = _id;
        this.inserted = inserted;
    }

    public boolean isInserted() {
        return inserted;
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }
}
