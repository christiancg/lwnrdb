package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

/**
 * Lists the trigger runs still recorded across the cluster. An optional {@code status} narrows the listing
 * to {@code PENDING} or {@code DEAD}; omitting it reports both.
 */
public class ListTriggerRunsRequest extends OperationRequest {
    private String status;

    public ListTriggerRunsRequest() {
        super(OperationType.LIST_TRIGGER_RUNS, null, null);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
