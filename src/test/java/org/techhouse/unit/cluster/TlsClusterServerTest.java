package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
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
import org.techhouse.conn.tls.SelfSignedCertificateGenerator;
import org.techhouse.conn.tls.TlsContextFactory;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestUtils;

public class TlsClusterServerTest {
    private static final String PASSWORD = "change_it";
    private static final String SECRET = "s";
    private Configuration config;
    private Path tempDir;
    private Path keystorePath;
    private ClusterServer server;
    private String origKeystorePath;
    private String origKeystorePassword;
    private boolean origClusterTls;
    private String origSecret;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        this.tempDir = tempDir;
        keystorePath = tempDir.resolve("cluster.p12");
        config = Configuration.getInstance();
        origKeystorePath = config.getTlsKeystorePath();
        origKeystorePassword = config.getTlsKeystorePassword();
        origClusterTls = config.isClusterTlsEnabled();
        origSecret = config.getClusterSecret();
        TestUtils.setPrivateField(config, "tlsKeystorePath", keystorePath.toString());
        TestUtils.setPrivateField(config, "tlsKeystorePassword", PASSWORD);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", true);
        TestUtils.setPrivateField(config, "clusterSecret", SECRET);
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
        final var serverFactory = TlsContextFactory.createClusterServerSocketFactory(config);
        assertNotNull(serverFactory);
        server = new ClusterServer(0, "127.0.0.1", serverFactory);
        assertTrue(assertStarts());
        assertNotNull(TlsContextFactory.createSocketFactory(config));
    }

    @Test
    public void theClusterSocketRequiresAClientCertificate() throws Exception {
        startServer();
        final var socket = TestUtils.getPrivateField(server, "serverSocket", ServerSocket.class);
        assertTrue(((SSLServerSocket) socket).getNeedClientAuth());
    }

    @Test
    public void aPeerWithTheSharedKeystoreHandshakes() throws Exception {
        startServer();
        final var pool = new PeerConnectionPool();
        try {
            final var response = pool.request(new NodeAddress("127.0.0.1", server.getPort()), new ClusterMessage(null,
                    ClusterMessageType.JOIN_REQUEST, SECRET, ClusterTestHarness.node("client", 2), null), 5000);
            assertEquals(ClusterMessageType.JOIN_RESPONSE, response.getType());
        } finally {
            pool.closeAll();
        }
    }

    @Test
    public void aClientWithoutAKeyHandshakeFails() throws Exception {
        startServer();
        final var trustOnly = SSLContext.getInstance("TLS");
        trustOnly.init(null, trustManagersOf(keystorePath), null);
        assertConnectionRefused(trustOnly);
    }

    @Test
    public void aClientWithAForeignCertificateFails() throws Exception {
        startServer();
        final var foreignPath = tempDir.resolve("foreign.p12");
        final var foreign = SelfSignedCertificateGenerator.generate(foreignPath, PASSWORD.toCharArray(), "lwnrdb");
        final var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(foreign, PASSWORD.toCharArray());
        final var foreignContext = SSLContext.getInstance("TLS");
        foreignContext.init(keyManagerFactory.getKeyManagers(), trustManagersOf(keystorePath), null);
        assertConnectionRefused(foreignContext);
    }

    private void startServer() {
        server = new ClusterServer(0, "127.0.0.1", TlsContextFactory.createClusterServerSocketFactory(config));
        assertTrue(assertStarts());
    }

    private static TrustManager[] trustManagersOf(Path keystore) throws Exception {
        final var keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        final var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);
        return trustManagerFactory.getTrustManagers();
    }

    private void assertConnectionRefused(SSLContext context) throws Exception {
        try (var socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5000);
            assertThrows(IOException.class, () -> {
                socket.startHandshake();
                final var out = socket.getOutputStream();
                out.write(("{\"type\":\"HELLO\",\"secret\":\"" + SECRET + "\"}\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (socket.getInputStream().read() < 0) {
                    throw new IOException("closed");
                }
            });
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
