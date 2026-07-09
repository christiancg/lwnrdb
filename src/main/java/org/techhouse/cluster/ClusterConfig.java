package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.List;
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
        return configuration.getClusterBindAddress();
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
        return configuration.getSuspectTimeoutMs();
    }

    public long deadTimeoutMs() {
        return configuration.getDeadTimeoutMs();
    }

    public long replicationAckTimeoutMs() {
        return configuration.getReplicationAckTimeoutMs();
    }

    public int virtualNodesPerNode() {
        return configuration.getVirtualNodesPerNode();
    }

    public boolean readFallbackToLocal() {
        return configuration.isReadFallbackToLocal();
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
}
