package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ResolveTransactionRequest extends OperationRequest {
    public static final String DECISION_COMMIT = "commit";
    public static final String DECISION_ABORT = "abort";

    private String dtxId;
    private String decision;

    public ResolveTransactionRequest() {
        super(OperationType.RESOLVE_TRANSACTION, null, null);
    }

    public String getDtxId() {
        return dtxId;
    }

    public void setDtxId(String dtxId) {
        this.dtxId = dtxId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }
}
