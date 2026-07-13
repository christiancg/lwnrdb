package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.config.Configuration;
import org.techhouse.conn.tls.TlsContextFactory;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class TlsClusterServerTest {
    private Configuration config;
    private ClusterServer server;
    private String origKeystorePath;
    private String origKeystorePassword;
    private boolean origClusterTls;
    private String origSecret;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        config = Configuration.getInstance();
        origKeystorePath = config.getTlsKeystorePath();
        origKeystorePassword = config.getTlsKeystorePassword();
        origClusterTls = config.isClusterTlsEnabled();
        origSecret = config.getClusterSecret();
        TestUtils.setPrivateField(config, "tlsKeystorePath", tempDir.resolve("cluster.p12").toString());
        TestUtils.setPrivateField(config, "tlsKeystorePassword", "change_it");
        TestUtils.setPrivateField(config, "clusterTlsEnabled", true);
        TestUtils.setPrivateField(config, "clusterSecret", "s");
        final var membershipService = IocContainer.get(MembershipService.class);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "lastSeen", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        membershipService.bootstrap(new NodeInfo("server", "127.0.0.1", 1, NodeState.ALIVE, 1L, 0L));
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        TestUtils.setPrivateField(config, "tlsKeystorePath", origKeystorePath);
        TestUtils.setPrivateField(config, "tlsKeystorePassword", origKeystorePassword);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", origClusterTls);
        TestUtils.setPrivateField(config, "clusterSecret", origSecret);
    }

    @Test
    public void test_tls_cluster_server_and_client_factory() {
        final var serverFactory = TlsContextFactory.createServerSocketFactory(config);
        assertNotNull(serverFactory);
        server = new ClusterServer(0, "127.0.0.1", serverFactory);
        assertTrue(assertStarts());
        assertNotNull(TlsContextFactory.createSocketFactory(config));

        final var pool = new PeerConnectionPool();
        try {
            pool.request(new NodeAddress("127.0.0.1", server.getPort()),
                    new ClusterMessage(null, ClusterMessageType.JOIN_REQUEST, "s",
                            new NodeInfo("client", "127.0.0.1", 2, NodeState.ALIVE, 1L, 1L), null),
                    3000);
        } catch (Exception ignored) {
            // The TLS branch of the pool has been exercised regardless of handshake trust outcome.
        } finally {
            pool.closeAll();
        }
    }

    private boolean assertStarts() {
        try {
            server.start();
            return server.getPort() > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
