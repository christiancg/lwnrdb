package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class SaveResponse extends OperationResponse {
    public String _id;
    public SaveResponse(OperationStatus status, String message, String _id) {
        super(OperationType.SAVE, status, message);
        this._id = _id;
    }
}
