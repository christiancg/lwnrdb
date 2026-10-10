package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ReleaseFaultRequest extends OperationRequest {
    private String point;

    public ReleaseFaultRequest() {
        super(OperationType.RELEASE_FAULT, null, null);
    }

    public String getPoint() {
        return point;
    }

    public void setPoint(String point) {
        this.point = point;
    }
}
