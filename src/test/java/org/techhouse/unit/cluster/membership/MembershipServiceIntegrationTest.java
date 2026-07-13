package org.techhouse.unit.cluster.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MembershipServiceIntegrationTest {
    private static final String SECRET = "s";
    private static final int SELF_PORT = 19990;
    private Configuration config;
    private ClusterServer server;
    private int serverPort;
    private MembershipService serverNode;
    private MembershipService client;
    private String origSecret;
    private String origSeeds;
    private String origNodeId;
    private String origAdvertised;
    private int origClusterPort;
    private long origGossip;
    private String origFilePath;

    @BeforeEach
    public void setUp() throws Exception {
        config = Configuration.getInstance();
        origSecret = config.getClusterSecret();
        origSeeds = config.getClusterSeeds();
        origNodeId = config.getNodeId();
        origAdvertised = config.getClusterAdvertisedAddress();
        origClusterPort = config.getClusterPort();
        origGossip = config.getGossipIntervalMs();
        origFilePath = config.getFilePath();
        TestUtils.setPrivateField(config, "clusterSecret", SECRET);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        TestUtils.setPrivateField(config, "clusterAdvertisedAddress", "127.0.0.1");
        TestUtils.setPrivateField(config, "clusterPort", SELF_PORT);
        TestUtils.setPrivateField(config, "gossipIntervalMs", 600000L);

        server = new ClusterServer(0, "127.0.0.1", null);
        server.start();
        serverPort = server.getPort();
        serverNode = IocContainer.get(MembershipService.class);
        TestUtils.setPrivateField(serverNode, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(serverNode, "lastSeen", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(serverNode, "self", null);
        serverNode.bootstrap(new NodeInfo("server", "127.0.0.1", serverPort, NodeState.ALIVE, 1L, 0L));
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (client != null) {
            client.stop();
        }
        server.stop();
        TestUtils.setPrivateField(config, "clusterSecret", origSecret);
        TestUtils.setPrivateField(config, "clusterSeeds", origSeeds);
        TestUtils.setPrivateField(config, "nodeId", origNodeId);
        TestUtils.setPrivateField(config, "clusterAdvertisedAddress", origAdvertised);
        TestUtils.setPrivateField(config, "clusterPort", origClusterPort);
        TestUtils.setPrivateField(config, "gossipIntervalMs", origGossip);
        TestUtils.setPrivateField(config, "filePath", origFilePath);
    }

    @Test
    public void test_start_joins_seed_and_exchanges_membership() throws Exception {
        TestUtils.setPrivateField(config, "clusterSeeds", "127.0.0.1:" + SELF_PORT + ",127.0.0.1:" + serverPort);
        TestUtils.setPrivateField(config, "nodeId", "client");
        client = new MembershipService();
        client.start();
        assertEquals("client", client.getSelf().getNodeId());
        assertNotNull(client.membershipView().find("server"));
        assertNotNull(serverNode.membershipView().find("client"));
    }

    @Test
    public void test_gossip_tick_reaches_peer() throws Exception {
        TestUtils.setPrivateField(config, "clusterSeeds", "127.0.0.1:" + serverPort);
        TestUtils.setPrivateField(config, "nodeId", "client2");
        client = new MembershipService();
        client.start();
        client.gossipTick();
        assertNotNull(client.membershipView().find("server"));
        assertTrue(client.getSelf().getHeartbeat() > 0);
        assertNotNull(serverNode.membershipView().find("client2"));
    }

    @Test
    public void test_unreachable_seed_is_tolerated() throws Exception {
        TestUtils.setPrivateField(config, "clusterSeeds", "127.0.0.1:2");
        TestUtils.setPrivateField(config, "nodeId", "lonely");
        client = new MembershipService();
        client.start();
        assertEquals("lonely", client.getSelf().getNodeId());
        assertEquals(1, client.membershipView().size());
    }

    @Test
    public void test_gossip_to_unreachable_member_is_tolerated() throws Exception {
        TestUtils.setPrivateField(config, "clusterSeeds", "");
        TestUtils.setPrivateField(config, "nodeId", "g");
        client = new MembershipService();
        client.start();
        client.handleGossip(new ClusterMessage(null, ClusterMessageType.GOSSIP, SECRET,
                new NodeInfo("ghost", "127.0.0.1", 2, NodeState.ALIVE, 1L, 1L), List.of()));
        client.gossipTickSafely();
        assertNotNull(client.membershipView().find("ghost"));
    }

    @Test
    public void test_node_id_is_generated_and_persisted(@TempDir Path tempDir) throws Exception {
        TestUtils.setPrivateField(config, "clusterSeeds", "");
        TestUtils.setPrivateField(config, "nodeId", "");
        TestUtils.setPrivateField(config, "filePath", tempDir.toString());
        client = new MembershipService();
        client.start();
        final var generatedId = client.getSelf().getNodeId();
        assertTrue(generatedId != null && !generatedId.isBlank());
        assertTrue(Files.exists(tempDir.resolve("cluster").resolve("node.id")));
        client.stop();

        final var reopened = new MembershipService();
        reopened.start();
        try {
            assertEquals(generatedId, reopened.getSelf().getNodeId());
        } finally {
            reopened.stop();
        }
    }
}
