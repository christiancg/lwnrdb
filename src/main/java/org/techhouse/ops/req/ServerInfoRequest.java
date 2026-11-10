package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ServerInfoRequest extends OperationRequest {
    private transient boolean shuttingDown;

    public ServerInfoRequest() {
        super(OperationType.SERVER_INFO, null, null);
    }

    public void markShuttingDown(boolean value) {
        shuttingDown = value;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }
}
