package org.techhouse.unit.conn.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.conn.BoundedLineReader;
import org.techhouse.conn.tls.SelfSignedCertificateGenerator;
import org.techhouse.conn.tls.TlsContextFactory;

public class BoundedLineReaderTlsTest {
    private static final int LIMIT = 1000;

    private static SSLServerSocket serverSocket(Path keystorePath) throws Exception {
        SelfSignedCertificateGenerator.generate(keystorePath, "change_it".toCharArray(), "lwnrdb");
        final var config = mock(Configuration.class);
        when(config.getTlsKeystorePath()).thenReturn(keystorePath.toString());
        when(config.getTlsKeystorePassword()).thenReturn("change_it");
        return (SSLServerSocket) TlsContextFactory.createServerSocketFactory(config).createServerSocket(0);
    }

    private static SSLSocket trustingClient(int port) throws Exception {
        final var trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        final var context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustAll}, null);
        return (SSLSocket) context.getSocketFactory().createSocket("localhost", port);
    }

    @Test
    public void test_a_tls_socket_keeps_the_partial_line_across_a_read_timeout(@TempDir Path tempDir) throws Exception {
        final var resume = new CountDownLatch(1);
        try (var server = serverSocket(tempDir.resolve("lwnrdb.p12"))) {
            final var client = CompletableFuture.runAsync(() -> {
                try (var socket = trustingClient(server.getLocalPort())) {
                    final var out = socket.getOutputStream();
                    out.write("{\"ty".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    resume.await();
                    out.write("pe\":1}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    assertEquals(-1, socket.getInputStream().read());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            try (var accepted = (SSLSocket) server.accept()) {
                final var reader = new BoundedLineReader(accepted.getInputStream(), accepted::setSoTimeout);

                try {
                    assertThrows(SocketTimeoutException.class, () -> reader.readLine(500, LIMIT));
                } finally {
                    resume.countDown();
                }
                assertEquals("{\"type\":1}", reader.readLine(10_000, LIMIT));
            }
            client.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void test_a_handshake_that_never_starts_times_out(@TempDir Path tempDir) throws Exception {
        try (var server = serverSocket(tempDir.resolve("lwnrdb.p12"));
                var plain = new Socket("localhost", server.getLocalPort());
                var accepted = (SSLSocket) server.accept()) {
            final var reader = new BoundedLineReader(accepted.getInputStream(), accepted::setSoTimeout);

            assertThrows(SocketTimeoutException.class, () -> reader.readLine(300, LIMIT));
            assertTrue(plain.isConnected());
        }
    }
}
