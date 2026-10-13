package org.techhouse.unit.conn.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.conn.SocketClosing;
import org.techhouse.conn.tls.SelfSignedCertificateGenerator;
import org.techhouse.conn.tls.TlsContextFactory;

public class TlsOutboundOverflowTest {
    private static final long QUEUE_CAP = 64 * 1024L;
    private static final String LINE = "x".repeat(16 * 1024);
    private static final long STALL_PROBE_MILLIS = 200L;
    private static final int MAX_LINES = 10_000;
    private static final long CLOSE_WITHIN_MILLIS = 6_000L;
    private SSLServerSocket server;
    private SSLSocket client;
    private SSLSocket accepted;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        final var keystore = tempDir.resolve("lwnrdb.p12");
        SelfSignedCertificateGenerator.generate(keystore, "change_it".toCharArray(), "lwnrdb");
        final var tlsConfig = mock(Configuration.class);
        when(tlsConfig.getTlsKeystorePath()).thenReturn(keystore.toString());
        when(tlsConfig.getTlsKeystorePassword()).thenReturn("change_it");
        server = (SSLServerSocket) TlsContextFactory.createServerSocketFactory(tlsConfig).createServerSocket(0);
        client = (SSLSocket) TlsContextFactory.createSocketFactory(tlsConfig).createSocket("127.0.0.1",
                server.getLocalPort());
        final var clientHandshake = CompletableFuture.runAsync(() -> {
            try {
                client.startHandshake();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        accepted = (SSLSocket) server.accept();
        SocketClosing.boundTlsClose(accepted);
        accepted.startHandshake();
        clientHandshake.get(CLOSE_WITHIN_MILLIS, TimeUnit.MILLISECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        accepted.close();
        server.close();
    }

    @Test
    public void test_an_accepted_tls_socket_lingers_for_a_bounded_close() throws Exception {
        assertEquals(Globals.TLS_CLOSE_LINGER_SECONDS, accepted.getSoLinger());
        try (var plain = new Socket()) {
            SocketClosing.boundTlsClose(plain);
            assertEquals(-1, plain.getSoLinger());
        }
    }

    @Test
    public void test_an_overflowing_tls_connection_closes_while_its_writer_is_stalled() throws Exception {
        final var overflows = new AtomicInteger();
        final var queue = new OutboundQueue(
                new BufferedWriter(new OutputStreamWriter(accepted.getOutputStream(), StandardCharsets.UTF_8)),
                accepted, QUEUE_CAP, OutboundQueue.VIRTUAL_DRAINER, overflows::incrementAndGet);
        var stalled = false;
        for (var line = 0; line < MAX_LINES && !stalled; line++) {
            queue.offer(LINE);
            stalled = !queue.awaitDrained(STALL_PROBE_MILLIS);
        }
        assertTrue(stalled, "the client never stopped the writer");
        for (var line = 0; line < MAX_LINES && queue.isOpen(); line++) {
            queue.offer(LINE);
        }
        assertFalse(queue.isOpen());
        assertEquals(1, overflows.get());
        assertTrue(queue.awaitDrained(CLOSE_WITHIN_MILLIS), "the stalled writer outlived the overflow close");
        assertTrue(accepted.isClosed());
    }
}
