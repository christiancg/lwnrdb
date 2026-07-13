package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class ClusterServerIntegrationTest {
    private static final String SECRET = "test-secret";
    private ClusterServer server;
    private PeerConnectionPool pool;
    private MembershipService membershipService;
    private String originalSecret;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    private ClusterMessage message(ClusterMessageType type, String secret, NodeInfo sender, List<NodeInfo> members) {
        return new ClusterMessage(null, type, secret, sender, members);
    }

    @BeforeEach
    public void setUp() throws Exception {
        final var configuration = Configuration.getInstance();
        originalSecret = configuration.getClusterSecret();
        TestUtils.setPrivateField(configuration, "clusterSecret", SECRET);
        TestUtils.setPrivateField(configuration, "clusterTlsEnabled", false);
        membershipService = IocContainer.get(MembershipService.class);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "lastSeen", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        membershipService.bootstrap(node("A", 9990));
        pool = IocContainer.get(PeerConnectionPool.class);
        server = new ClusterServer(0, "127.0.0.1", null);
        server.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        pool.closeAll();
        server.stop();
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterSecret", originalSecret);
    }

    private NodeAddress serverAddress() {
        return new NodeAddress("127.0.0.1", server.getPort());
    }

    @Test
    public void test_join_request_returns_members_and_registers_sender() throws Exception {
        final var response = pool.request(serverAddress(),
                message(ClusterMessageType.JOIN_REQUEST, SECRET, node("B", 9991), null), 3000);
        assertEquals(ClusterMessageType.JOIN_RESPONSE, response.getType());
        assertNotNull(response.getMembers());
        assertNotNull(membershipService.membershipView().find("A"));
        assertNotNull(membershipService.membershipView().find("B"));
    }

    @Test
    public void test_gossip_merges_sender_and_members() throws Exception {
        final var response = pool.request(serverAddress(),
                message(ClusterMessageType.GOSSIP, SECRET, node("C", 9992), List.of(node("D", 9993))), 3000);
        assertEquals(ClusterMessageType.GOSSIP_ACK, response.getType());
        assertNotNull(membershipService.membershipView().find("C"));
        assertNotNull(membershipService.membershipView().find("D"));
    }

    @Test
    public void test_wrong_secret_is_rejected() throws Exception {
        final var response = pool.request(serverAddress(),
                message(ClusterMessageType.JOIN_REQUEST, "wrong", node("E", 9994), null), 3000);
        assertEquals(ClusterMessageType.ERROR, response.getType());
        assertEquals("Invalid cluster secret", response.getErrorMessage());
    }

    @Test
    public void test_request_to_unreachable_node_fails() {
        server.stop();
        assertThrows(Exception.class, () -> pool.request(serverAddress(),
                message(ClusterMessageType.GOSSIP, SECRET, node("F", 9995), null), 2000));
    }

    @Test
    public void test_unsupported_message_type_is_rejected() throws Exception {
        final var response = pool.request(serverAddress(),
                message(ClusterMessageType.JOIN_RESPONSE, SECRET, node("G", 9996), null), 3000);
        assertEquals(ClusterMessageType.ERROR, response.getType());
        assertEquals("Unsupported cluster message type: JOIN_RESPONSE", response.getErrorMessage());
    }

    @Test
    public void test_get_port_before_start_returns_configured_port() {
        assertEquals(12345, new ClusterServer(12345, "127.0.0.1", null).getPort());
    }

    @Test
    public void test_replicate_with_invalid_payload_is_nacked() throws Exception {
        final var request = message(ClusterMessageType.REPLICATE, SECRET, node("H", 9997), null);
        request.setReplication(new ReplicationPayload());
        final var response = pool.request(serverAddress(), request, 3000);
        assertEquals(ClusterMessageType.ERROR, response.getType());
    }

    @Test
    public void test_replicate_user_with_invalid_payload_is_nacked() throws Exception {
        final var request = message(ClusterMessageType.REPLICATE_USER, SECRET, node("I", 9998), null);
        request.setReplication(new ReplicationPayload());
        final var response = pool.request(serverAddress(), request, 3000);
        assertEquals(ClusterMessageType.ERROR, response.getType());
    }

    @Test
    public void test_forward_with_unparseable_body_is_error() throws Exception {
        final var request = message(ClusterMessageType.FORWARD_REQUEST, SECRET, node("J", 9999), null);
        request.setForwardBody(ForwardBody.encode("not-valid-json"));
        final var response = pool.request(serverAddress(), request, 3000);
        assertEquals(ClusterMessageType.ERROR, response.getType());
    }

    @Test
    public void test_digest_request_is_handled() throws Exception {
        final var request = message(ClusterMessageType.DIGEST, SECRET, node("K", 9990), null);
        request.setAntiEntropy(new AntiEntropyPayload("someDb", "someColl"));
        final var response = pool.request(serverAddress(), request, 3000);
        assertTrue(
                response.getType() == ClusterMessageType.DIGEST_ACK || response.getType() == ClusterMessageType.ERROR);
    }

    @Test
    public void test_pull_request_is_handled() throws Exception {
        final var request = message(ClusterMessageType.PULL, SECRET, node("L", 9990), null);
        final var payload = new AntiEntropyPayload("someDb", "someColl");
        payload.setIds(List.of("a"));
        request.setAntiEntropy(payload);
        final var response = pool.request(serverAddress(), request, 3000);
        assertTrue(response.getType() == ClusterMessageType.PULL_ACK || response.getType() == ClusterMessageType.ERROR);
    }
}
