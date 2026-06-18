package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.conn.SocketServer;
import org.techhouse.conn.tls.SelfSignedCertificateGenerator;
import org.techhouse.conn.tls.TlsContextFactory;

public class SocketServerTest {

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static SSLServerSocketFactory serverFactory(Path keystorePath) {
        SelfSignedCertificateGenerator.generate(keystorePath, "change_it".toCharArray(), "lwnrdb");
        final var config = mock(Configuration.class);
        when(config.getTlsKeystorePath()).thenReturn(keystorePath.toString());
        when(config.getTlsKeystorePassword()).thenReturn("change_it");
        return TlsContextFactory.createServerSocketFactory(config);
    }

    private static SSLContext trustAllClientContext() throws Exception {
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
        return context;
    }

    @Test
    public void test_tls_server_accepts_tls_client(@TempDir Path tempDir) throws Exception {
        final int port = freePort();
        final var factory = serverFactory(tempDir.resolve("lwnrdb.p12"));
        //noinspection resource
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SocketServer server = new SocketServer(port, factory);
        executor.submit(server::serve);
        Thread.sleep(200);
        try (SSLSocket socket = (SSLSocket) trustAllClientContext().getSocketFactory().createSocket("localhost",
                port)) {
            socket.startHandshake();
            assertTrue(socket.getSession().isValid());
            assertNotNull(socket.getSession().getCipherSuite());
            assertFalse(socket.getSession().getCipherSuite().contains("NULL"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void test_tls_server_rejects_plaintext_client(@TempDir Path tempDir) throws Exception {
        final int port = freePort();
        final var factory = serverFactory(tempDir.resolve("lwnrdb.p12"));
        //noinspection resource
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SocketServer server = new SocketServer(port, factory);
        executor.submit(server::serve);
        Thread.sleep(200);
        try {
            String line;
            // A plaintext client sends JSON the server interprets as a (malformed) TLS record.
            try (Socket plain = new Socket("localhost", port)) {
                final OutputStream out = plain.getOutputStream();
                out.write("{\"type\":\"CLOSE_CONNECTION\"}\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                final var reader = new BufferedReader(
                        new InputStreamReader(plain.getInputStream(), StandardCharsets.UTF_8));
                line = reader.readLine();
            } catch (IOException expected) {
                // Connection reset during the failed handshake is also an acceptable rejection.
                line = null;
            }
            // The handshake fails server-side: the client gets a TLS alert / closed connection, never a
            // valid JSON response. So either nothing readable, or bytes that are not a real response.
            assertTrue(line == null || !line.contains("status"),
                    "plaintext client must not receive a valid JSON response, got: " + line);
            // The server survives and still accepts a proper TLS client afterwards.
            try (SSLSocket socket = (SSLSocket) trustAllClientContext().getSocketFactory().createSocket("localhost",
                    port)) {
                socket.startHandshake();
                assertTrue(socket.getSession().isValid());
            }
        } finally {
            executor.shutdownNow();
        }
    }
    // Server starts and listens on the specified port
    @Test
    public void test_server_starts_and_listens_on_specified_port() throws InterruptedException {
        int port = 8080;
        //noinspection resource
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SocketServer server = new SocketServer(port);
        executor.submit(server::serve);
        Thread.sleep(100);
        try (Socket socket = new Socket("localhost", port)) {
            assertTrue(socket.isConnected());
        } catch (IOException e) {
            fail("Server did not start or listen on the specified port");
        } finally {
            executor.shutdownNow();
        }
    }

    // Port is already in use when starting the server
    @Test
    public void test_port_already_in_use_when_starting_server() {
        int port = 8080;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            try (ServerSocket ignored = new ServerSocket(port)) {
                SocketServer server = new SocketServer(port);
                executor.submit(server::serve);
                Thread.sleep(100); // Wait for the server to attempt to start
                // Check logs or other indicators for failure due to port in use
                // This is a placeholder for actual log checking or exception handling
            } catch (IOException | InterruptedException e) {
                fail("Unexpected exception occurred");
            }
        }
    }
}
