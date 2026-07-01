package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class StopListenRequest extends OperationRequest {
    private String listenId;

    public StopListenRequest() {
        super(OperationType.STOP_LISTEN, null, null);
    }

    public String getListenId() {
        return listenId;
    }

    public void setListenId(String listenId) {
        this.listenId = listenId;
    }
}
