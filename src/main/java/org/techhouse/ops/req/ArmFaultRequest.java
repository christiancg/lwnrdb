package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ArmFaultRequest extends OperationRequest {
    private String point;
    private String action;
    private String match;
    private Long delayMs;

    public ArmFaultRequest() {
        super(OperationType.ARM_FAULT, null, null);
    }

    public String getPoint() {
        return point;
    }

    public void setPoint(String point) {
        this.point = point;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMatch() {
        return match;
    }

    public void setMatch(String match) {
        this.match = match;
    }

    public Long getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(Long delayMs) {
        this.delayMs = delayMs;
    }
}
