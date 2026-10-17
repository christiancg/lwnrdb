package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;

public class ClusterConfig {
    private final Configuration configuration;

    public ClusterConfig() {
        this(Configuration.getInstance());
    }

    public ClusterConfig(Configuration configuration) {
        this.configuration = configuration;
    }

    public boolean isEnabled() {
        return configuration.isClusterEnabled();
    }

    public int clusterPort() {
        return configuration.getClusterPort();
    }

    public String bindAddress() {
        return configuration.stringValue(ConfigKey.CLUSTER_BIND_ADDRESS);
    }

    public String advertisedAddress() {
        return configuration.getClusterAdvertisedAddress();
    }

    public List<NodeAddress> seeds() {
        final var result = new ArrayList<NodeAddress>();
        final var raw = configuration.getClusterSeeds();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (var seed : raw.split(Globals.CLUSTER_SEED_SEPARATOR)) {
            final var trimmed = seed.trim();
            if (!trimmed.isEmpty()) {
                result.add(NodeAddress.parse(trimmed));
            }
        }
        return result;
    }

    public String configuredNodeId() {
        return configuration.getNodeId();
    }

    public int expectedSize() {
        return configuration.getClusterExpectedSize();
    }

    public long gossipIntervalMs() {
        return configuration.getGossipIntervalMs();
    }

    public long suspectTimeoutMs() {
        return configuration.longValue(ConfigKey.SUSPECT_TIMEOUT_MS);
    }

    public long deadTimeoutMs() {
        return configuration.getDeadTimeoutMs();
    }

    public long deadEvictionMs() {
        return configuration.getDeadEvictionMs();
    }

    public long deadProbeIntervalMs() {
        return configuration.longValue(ConfigKey.DEAD_PROBE_INTERVAL_MS);
    }

    public long replicationAckTimeoutMs() {
        return configuration.getReplicationAckTimeoutMs();
    }

    public int virtualNodesPerNode() {
        return configuration.intValue(ConfigKey.VIRTUAL_NODES_PER_NODE);
    }

    public boolean readFallbackToLocal() {
        return configuration.booleanValue(ConfigKey.READ_FALLBACK_TO_LOCAL);
    }

    public boolean scriptRoutingEnabled() {
        return configuration.isScriptRoutingEnabled();
    }

    public int scriptLocalityWeight() {
        return configuration.getScriptLocalityWeight();
    }

    public boolean tlsEnabled() {
        return configuration.isClusterTlsEnabled();
    }

    public String secret() {
        return configuration.getClusterSecret();
    }

    public long antiEntropyIntervalMs() {
        return configuration.getAntiEntropyIntervalMs();
    }

    public long tombstoneRetentionMs() {
        return configuration.getTombstoneRetentionMs();
    }

    public int maxFrameBytes() {
        return (int) Math.min(configuration.sizeValue(ConfigKey.CLUSTER_MAX_FRAME_SIZE), Integer.MAX_VALUE);
    }

    public long handshakeTimeoutMs() {
        return configuration.longValue(ConfigKey.CLUSTER_HANDSHAKE_TIMEOUT_MS);
    }

    public long maxClockSkewMs() {
        return configuration.longValue(ConfigKey.CLUSTER_MAX_CLOCK_SKEW_MS);
    }
}
