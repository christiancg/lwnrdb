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
    // Raw request JSON on a FORWARD_REQUEST; raw response JSON on a FORWARD_RESPONSE.
    private String forwardBody;
    // Authenticated username the edge resolved, carried on forwarded/replicated admin ops so the executing
    // node applies them as the same acting user (e.g. CREATE_DATABASE owner assignment).
    private String actingUser;
    // Digest / pull payload on DIGEST(_ACK) and PULL(_ACK) messages used by anti-entropy reconciliation.
    private AntiEntropyPayload antiEntropy;
    // Stable per-connection transaction session id (the edge client id) on a FORWARD_TX_REQUEST, so the
    // owner keeps the same buffered transaction across the session's forwarded operations.
    private String txSessionId;
    // The distributed-transaction id (the coordinator's transaction UUID) on 2PC control messages, used as
    // the correlation key for the durable recovery-log markers.
    private String txId;
    // The full participant-address set on a PREPARE_TX, so a stuck participant can poll its peers during
    // cooperative termination.
    private List<String> txParticipants;
    // A participant's knowledge of a transaction on a TX_STATUS_ACK (COMMITTED/ABORTED/PREPARED/UNKNOWN).
    private String txStatus;
    // A committed transaction's atomic write batch on a REPLICATE_TX message.
    private TxReplicationPayload txReplication;
    // This node's in-doubt (PREPARED) distributed transactions on a LIST_TX_ACK, aggregated cluster-wide by
    // the node handling a LIST_TRANSACTIONS request.
    private List<InDoubtTx> inDoubtTransactions;
    // This node's authoritative admin state (epoch + databases/collections/users) on an ADMIN_SNAPSHOT_ACK.
    private AdminSnapshotPayload adminSnapshot;
    // The admin coordinator's current admin epoch, shipped on REPLICATE_ADMIN/REPLICATE_USER so live replicas
    // advance without pulling a snapshot.
    private long adminEpoch;
    private String errorMessage;

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
