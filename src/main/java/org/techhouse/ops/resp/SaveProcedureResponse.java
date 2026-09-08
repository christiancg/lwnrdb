package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class SaveProcedureResponse extends OperationResponse {
    private final long version;

    public SaveProcedureResponse(String message, long version) {
        super(OperationType.SAVE_PROCEDURE, OperationStatus.OK, message);
        this.version = version;
    }

    public long getVersion() {
        return version;
    }
}
