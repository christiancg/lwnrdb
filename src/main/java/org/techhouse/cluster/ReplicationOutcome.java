package org.techhouse.cluster;

public enum ReplicationOutcome {
    NOT_CLUSTERED, NOT_OWNER, NOT_COORDINATOR, QUORUM_MET, TIMEOUT
}
