package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class StopListenResponse extends OperationResponse {
    public StopListenResponse() {
        super(OperationType.STOP_LISTEN, OperationStatus.OK, "Stopped listening");
    }
}
