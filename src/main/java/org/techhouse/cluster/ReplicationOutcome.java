package org.techhouse.cluster;

public enum ReplicationOutcome {
    // The write did not need cluster replication (clustering disabled, admin data, or this node is not the owner).
    NOT_APPLICABLE,
    // A majority of the cluster acknowledged the replicated write.
    QUORUM_MET,
    // The replication quorum was not reached before the timeout; the local commit still stands.
    TIMEOUT
}
