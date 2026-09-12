package org.techhouse.cluster.msg;

import java.util.List;
import org.techhouse.cluster.NodeInfo;

public class ClusterMessage {
    private String correlationId;
    private ClusterMessageType type;
    private String secret;
    private NodeInfo sender;
    private List<NodeInfo> members;
    private ReplicationPayload replication;
    private String forwardBody;
    private String actingUser;
    private AntiEntropyPayload antiEntropy;
    private String txSessionId;
    private String txId;
    private List<String> txParticipants;
    private String txStatus;
    private TxReplicationPayload txReplication;
    private List<InDoubtTx> inDoubtTransactions;
    private List<RunningScript> runningScripts;
    private String cancelRunId;
    private List<TriggerRunRow> triggerRuns;
    private String triggerRunId;
    private String triggerRunDecision;
    private boolean triggerRunResolved;
    private boolean cancelledRun;
    private AdminSnapshotPayload adminSnapshot;
    private long adminEpoch;
    private String errorMessage;

    public List<TriggerRunRow> getTriggerRuns() {
        return triggerRuns;
    }

    public void setTriggerRuns(List<TriggerRunRow> triggerRuns) {
        this.triggerRuns = triggerRuns;
    }

    public String getTriggerRunId() {
        return triggerRunId;
    }

    public void setTriggerRunId(String triggerRunId) {
        this.triggerRunId = triggerRunId;
    }

    public String getTriggerRunDecision() {
        return triggerRunDecision;
    }

    public void setTriggerRunDecision(String triggerRunDecision) {
        this.triggerRunDecision = triggerRunDecision;
    }

    public boolean isTriggerRunResolved() {
        return triggerRunResolved;
    }

    public void setTriggerRunResolved(boolean triggerRunResolved) {
        this.triggerRunResolved = triggerRunResolved;
    }

    public ClusterMessage() {
    }

    public ClusterMessage(String correlationId, ClusterMessageType type, String secret, NodeInfo sender,
            List<NodeInfo> members) {
        this.correlationId = correlationId;
        this.type = type;
        this.secret = secret;
        this.sender = sender;
        this.members = members;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public ClusterMessageType getType() {
        return type;
    }

    public void setType(ClusterMessageType type) {
        this.type = type;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public NodeInfo getSender() {
        return sender;
    }

    public void setSender(NodeInfo sender) {
        this.sender = sender;
    }

    public List<NodeInfo> getMembers() {
        return members;
    }

    public void setMembers(List<NodeInfo> members) {
        this.members = members;
    }

    public ReplicationPayload getReplication() {
        return replication;
    }

    public void setReplication(ReplicationPayload replication) {
        this.replication = replication;
    }

    public String getForwardBody() {
        return forwardBody;
    }

    public void setForwardBody(String forwardBody) {
        this.forwardBody = forwardBody;
    }

    public String getActingUser() {
        return actingUser;
    }

    public void setActingUser(String actingUser) {
        this.actingUser = actingUser;
    }

    public AntiEntropyPayload getAntiEntropy() {
        return antiEntropy;
    }

    public void setAntiEntropy(AntiEntropyPayload antiEntropy) {
        this.antiEntropy = antiEntropy;
    }

    public String getTxSessionId() {
        return txSessionId;
    }

    public void setTxSessionId(String txSessionId) {
        this.txSessionId = txSessionId;
    }

    public String getTxId() {
        return txId;
    }

    public void setTxId(String txId) {
        this.txId = txId;
    }

    public List<String> getTxParticipants() {
        return txParticipants;
    }

    public void setTxParticipants(List<String> txParticipants) {
        this.txParticipants = txParticipants;
    }

    public String getTxStatus() {
        return txStatus;
    }

    public void setTxStatus(String txStatus) {
        this.txStatus = txStatus;
    }

    public TxReplicationPayload getTxReplication() {
        return txReplication;
    }

    public void setTxReplication(TxReplicationPayload txReplication) {
        this.txReplication = txReplication;
    }

    public List<InDoubtTx> getInDoubtTransactions() {
        return inDoubtTransactions;
    }

    public void setInDoubtTransactions(List<InDoubtTx> inDoubtTransactions) {
        this.inDoubtTransactions = inDoubtTransactions;
    }

    public List<RunningScript> getRunningScripts() {
        return runningScripts;
    }

    public void setRunningScripts(List<RunningScript> runningScripts) {
        this.runningScripts = runningScripts;
    }

    public String getCancelRunId() {
        return cancelRunId;
    }

    public void setCancelRunId(String cancelRunId) {
        this.cancelRunId = cancelRunId;
    }

    public boolean isCancelledRun() {
        return cancelledRun;
    }

    public void setCancelledRun(boolean cancelledRun) {
        this.cancelledRun = cancelledRun;
    }

    public AdminSnapshotPayload getAdminSnapshot() {
        return adminSnapshot;
    }

    public void setAdminSnapshot(AdminSnapshotPayload adminSnapshot) {
        this.adminSnapshot = adminSnapshot;
    }

    public long getAdminEpoch() {
        return adminEpoch;
    }

    public void setAdminEpoch(long adminEpoch) {
        this.adminEpoch = adminEpoch;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
