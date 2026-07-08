package org.techhouse.cluster.msg;

public enum ClusterMessageType {
    JOIN_REQUEST, JOIN_RESPONSE, GOSSIP, GOSSIP_ACK, REPLICATE, REPLICATE_ACK, ERROR
}
