package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ResolveTriggerRunRequest extends OperationRequest {
    public static final String DECISION_REPLAY = "replay";
    public static final String DECISION_DISCARD = "discard";

    private String runId;
    private String decision;

    public ResolveTriggerRunRequest() {
        super(OperationType.RESOLVE_TRIGGER_RUN, null, null);
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }
}
