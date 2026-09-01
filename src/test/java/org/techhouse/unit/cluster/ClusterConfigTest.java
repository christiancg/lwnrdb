package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

public class ClusterConfigTest {

    private static Configuration newConfiguration() throws Exception {
        final var constructor = Configuration.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static ClusterConfig configWith(String seeds) throws Exception {
        final var configuration = newConfiguration();
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        TestUtils.setPrivateField(configuration, "clusterPort", 9990);
        TestUtils.setPrivateField(configuration, "clusterBindAddress", "0.0.0.0");
        TestUtils.setPrivateField(configuration, "clusterAdvertisedAddress", "10.0.0.1");
        TestUtils.setPrivateField(configuration, "clusterSeeds", seeds);
        TestUtils.setPrivateField(configuration, "nodeId", "node-x");
        TestUtils.setPrivateField(configuration, "clusterExpectedSize", 3);
        TestUtils.setPrivateField(configuration, "gossipIntervalMs", 1000L);
        TestUtils.setPrivateField(configuration, "suspectTimeoutMs", 5000L);
        TestUtils.setPrivateField(configuration, "deadTimeoutMs", 15000L);
        TestUtils.setPrivateField(configuration, "replicationAckTimeoutMs", 2000L);
        TestUtils.setPrivateField(configuration, "virtualNodesPerNode", 64);
        TestUtils.setPrivateField(configuration, "readFallbackToLocal", true);
        TestUtils.setPrivateField(configuration, "scriptRoutingEnabled", true);
        TestUtils.setPrivateField(configuration, "clusterTlsEnabled", false);
        TestUtils.setPrivateField(configuration, "clusterSecret", "top-secret");
        return new ClusterConfig(configuration);
    }

    @Test
    public void test_getters_reflect_configuration() throws Exception {
        final var config = configWith("");
        assertTrue(config.isEnabled());
        assertEquals(9990, config.clusterPort());
        assertEquals("0.0.0.0", config.bindAddress());
        assertEquals("10.0.0.1", config.advertisedAddress());
        assertEquals("node-x", config.configuredNodeId());
        assertEquals(3, config.expectedSize());
        assertEquals(1000L, config.gossipIntervalMs());
        assertEquals(5000L, config.suspectTimeoutMs());
        assertEquals(15000L, config.deadTimeoutMs());
        assertEquals(2000L, config.replicationAckTimeoutMs());
        assertEquals(64, config.virtualNodesPerNode());
        assertTrue(config.readFallbackToLocal());
        assertTrue(config.scriptRoutingEnabled());
        assertFalse(config.tlsEnabled());
        assertEquals("top-secret", config.secret());
    }

    @Test
    public void test_seeds_parsed_from_csv() throws Exception {
        final var config = configWith("host1:9990, host2:9991 ,host3:9992");
        assertEquals(
                List.of(new NodeAddress("host1", 9990), new NodeAddress("host2", 9991), new NodeAddress("host3", 9992)),
                config.seeds());
    }

    @Test
    public void test_empty_seeds_returns_empty_list() throws Exception {
        assertTrue(configWith("").seeds().isEmpty());
        assertTrue(configWith("   ").seeds().isEmpty());
    }

    @Test
    public void test_blank_seed_entries_are_skipped() throws Exception {
        final var config = configWith("host1:9990,,host2:9991");
        assertEquals(2, config.seeds().size());
    }
}
