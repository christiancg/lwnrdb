package org.techhouse.test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;

public final class ClusterTestHarness {
    public static final String SECRET = "s";
    private static final long DEFAULT_ACK_TIMEOUT_MS = 1500L;

    private final Configuration config = Configuration.getInstance();
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private ClusterServer server;
    private int serverPort;
    private boolean origEnabled;
    private String origSecret;
    private boolean origTls;
    private long origAck;
    private int origExpected;

    public static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    public void start() throws Exception {
        start(true, DEFAULT_ACK_TIMEOUT_MS);
    }

    public void start(boolean clusterEnabled, long ackTimeoutMs) throws Exception {
        origEnabled = config.isClusterEnabled();
        origSecret = config.getClusterSecret();
        origTls = config.isClusterTlsEnabled();
        origAck = config.getReplicationAckTimeoutMs();
        origExpected = config.getClusterExpectedSize();
        if (clusterEnabled) {
            TestUtils.setPrivateField(config, "clusterEnabled", true);
        }
        TestUtils.setPrivateField(config, "clusterSecret", SECRET);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", ackTimeoutMs);
        server = new ClusterServer(0, "127.0.0.1", null);
        server.start();
        serverPort = server.getPort();
    }

    public void stop() throws Exception {
        pool.closeAll();
        server.stop();
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterSecret", origSecret);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", origTls);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origAck);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "lastSeen", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
    }

    public int serverPort() {
        return serverPort;
    }

    public NodeAddress serverAddress() {
        return new NodeAddress("127.0.0.1", serverPort);
    }

    public void configureMembership(int expectedSize, NodeInfo self, NodeInfo... others) throws Exception {
        TestUtils.setPrivateField(config, "clusterExpectedSize", expectedSize);
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        members.put(self.getNodeId(), self);
        for (final var other : others) {
            members.put(other.getNodeId(), other);
        }
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId(self.getNodeId());
        ownership.onMembershipChanged(membershipService.membershipView());
    }

    public void configureRemoteCoordinator() throws Exception {
        for (var i = 0; i < 500; i++) {
            configureMembership(2, node("self", 19990), node("coord-" + i, serverPort));
            if (!ownership.isAdminCoordinator()) {
                return;
            }
        }
        throw new IllegalStateException("could not configure self as a non-coordinator");
    }
}
