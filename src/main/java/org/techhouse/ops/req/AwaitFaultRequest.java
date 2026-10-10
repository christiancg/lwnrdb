package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class AwaitFaultRequest extends OperationRequest {
    private String point;
    private Long timeoutMs;

    public AwaitFaultRequest() {
        super(OperationType.AWAIT_FAULT, null, null);
    }

    public String getPoint() {
        return point;
    }

    public void setPoint(String point) {
        this.point = point;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
