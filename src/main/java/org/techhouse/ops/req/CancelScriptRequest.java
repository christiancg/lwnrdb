package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class CancelScriptRequest extends OperationRequest {
    private String runId;

    public CancelScriptRequest() {
        super(OperationType.CANCEL_SCRIPT, null, null);
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }
}
