package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.Mockito;
import org.techhouse.cluster.ClusterConnectionHandler;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.PeerUnreachableException;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;
import org.techhouse.test.TestUtils;

public class ClusterHandshakeDeadlineTest {
    private static final long SHORT_HANDSHAKE_MS = 200L;
    private final Configuration config = Configuration.getInstance();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.setPrivateField(config, "clusterHandshakeTimeoutMs", SHORT_HANDSHAKE_MS);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterHandshakeTimeoutMs",
                ConfigKey.CLUSTER_HANDSHAKE_TIMEOUT_MS.defaultValue());
        TestUtils.standardTearDown();
    }

    private static InputStream silentUntil(CountDownLatch closed) {
        return new InputStream() {
            @Override
            public int read() {
                try {
                    closed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
    }

    @Test
    public void test_the_cluster_port_enforces_a_handshake_deadline() throws Exception {
        final var closed = new CountDownLatch(1);
        final var socket = TestUtils.clientSocket(silentUntil(closed), new ByteArrayOutputStream());
        Mockito.doAnswer(_ -> {
            closed.countDown();
            return null;
        }).when(socket).close();
        final var handshakeDone = new AtomicInteger();

        final var handler = Thread.ofPlatform().daemon()
                .start(new ClusterConnectionHandler(socket, () -> false, handshakeDone::incrementAndGet));
        handler.join(SHORT_HANDSHAKE_MS + Globals.HANDSHAKE_DEADLINE_GRACE_MS + 5000);

        assertFalse(handler.isAlive(), "a peer that never completes its handshake is cut off");
        assertTrue(handshakeDone.get() >= 1);
        Mockito.verify(socket, Mockito.atLeastOnce()).close();
    }

    private static InputStream resetting() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Connection reset");
            }
        };
    }

    @Test
    public void resetDuringHelloIsRateLimited() throws Exception {
        final var logger = Mockito.mock(Logger.class);
        final var warnings = new AtomicInteger();
        Mockito.doAnswer(_ -> warnings.incrementAndGet()).when(logger).warning(Mockito.anyString());
        final var resets = Globals.LOG_RATE_LIMIT_PER_SECOND * 5;

        for (var i = 0; i < resets; i++) {
            final var socket = TestUtils.clientSocket(resetting(), new ByteArrayOutputStream());
            final var handler = new ClusterConnectionHandler(socket, () -> false, () -> {
            });
            TestUtils.setPrivateField(handler, "logger", logger);
            handler.run();
        }

        assertTrue(warnings.get() <= Globals.LOG_RATE_LIMIT_PER_SECOND * 2, "warnings logged: " + warnings.get());
    }

    @Test
    public void test_outbound_cluster_sockets_pin_tls_protocols() throws Exception {
        final var sslSocket = Mockito.mock(SSLSocket.class);
        Mockito.doThrow(new IOException("refused")).when(sslSocket).connect(Mockito.any(InetSocketAddress.class),
                Mockito.anyInt());
        final var factory = Mockito.mock(SocketFactory.class);
        Mockito.when(factory.createSocket()).thenReturn(sslSocket);
        final var pool = new PeerConnectionPool();
        TestUtils.setPrivateField(pool, "socketFactory", factory);

        assertThrows(PeerUnreachableException.class,
                () -> pool.request(new NodeAddress("127.0.0.1", 1), new ClusterMessage(), 100));

        final var order = Mockito.inOrder(sslSocket);
        order.verify(sslSocket).setEnabledProtocols(AdditionalMatchers.aryEq(new String[]{"TLSv1.3", "TLSv1.2"}));
        order.verify(sslSocket).connect(Mockito.any(InetSocketAddress.class), Mockito.anyInt());
    }
}
