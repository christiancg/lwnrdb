package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

/**
 * Replays or discards one dead-lettered trigger run. {@code replay} resets its attempt count and re-queues it
 * on the node holding it; {@code discard} consumes its record, giving up on the run for good.
 */
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
