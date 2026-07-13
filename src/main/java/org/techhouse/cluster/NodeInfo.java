package org.techhouse.cluster;

import java.util.Objects;

public class NodeInfo {
    // nodeId is set once (construction/deserialization); the rest are mutated across the gossip and
    // connection-handler threads, so they are volatile for cross-thread visibility and 64-bit atomicity.
    private String nodeId;
    private volatile String host;
    private volatile int port;
    private volatile NodeState state;
    private volatile long incarnation;
    private volatile long heartbeat;

    public NodeInfo() {
    }

    public NodeInfo(String nodeId, String host, int port, NodeState state, long incarnation, long heartbeat) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.state = state;
        this.incarnation = incarnation;
        this.heartbeat = heartbeat;
    }

    public NodeAddress address() {
        return new NodeAddress(host, port);
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public NodeState getState() {
        return state;
    }

    public void setState(NodeState state) {
        this.state = state;
    }

    public long getIncarnation() {
        return incarnation;
    }

    public void setIncarnation(long incarnation) {
        this.incarnation = incarnation;
    }

    public long getHeartbeat() {
        return heartbeat;
    }

    public void setHeartbeat(long heartbeat) {
        this.heartbeat = heartbeat;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof NodeInfo that))
            return false;
        return port == that.port && incarnation == that.incarnation && heartbeat == that.heartbeat
                && Objects.equals(nodeId, that.nodeId) && Objects.equals(host, that.host) && state == that.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, host, port, state, incarnation, heartbeat);
    }

    @Override
    public String toString() {
        return "NodeInfo(nodeId=" + nodeId + ", host=" + host + ", port=" + port + ", state=" + state + ", incarnation="
                + incarnation + ", heartbeat=" + heartbeat + ")";
    }
}
