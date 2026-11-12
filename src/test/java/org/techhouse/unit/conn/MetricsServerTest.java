package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.MetricsServer;
import org.techhouse.metrics.PrometheusText;

public class MetricsServerTest {
    private static final String LOOPBACK = "127.0.0.1";
    private static final String BODY = "lwnrdb_build_info{version=\"x\"} 1\n";
    private static final int READ_TIMEOUT_MS = 1500;
    private final List<Socket> held = new ArrayList<>();
    private MetricsServer server;

    @AfterEach
    void tearDown() throws IOException {
        for (final var socket : held) {
            socket.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    private void started(String token) throws IOException {
        started(token, () -> BODY);
    }

    private void started(String token, Supplier<String> exposition) throws IOException {
        server = new MetricsServer(exposition, READ_TIMEOUT_MS);
        server.start(0, LOOPBACK, token);
    }

    private String exchange(String request) throws IOException {
        return send(request.getBytes(StandardCharsets.ISO_8859_1));
    }

    private String send(byte[] request) throws IOException {
        try (var socket = new Socket(LOOPBACK, server.boundPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String get(String path, String... headers) throws IOException {
        return exchange("GET " + path + " HTTP/1.1\r\nHost: test\r\n" + String.join("", headers) + "\r\n");
    }

    private static String bodyOf(String response) {
        return response.substring(response.indexOf("\r\n\r\n") + 4);
    }

    @Test
    public void test_get_answers_the_exposition_with_the_prometheus_content_type() throws IOException {
        started("");

        final var response = get("/metrics");

        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
        assertTrue(response.contains("Content-Type: " + PrometheusText.CONTENT_TYPE + "\r\n"), response);
        assertTrue(response.contains("Content-Length: " + BODY.length() + "\r\n"), response);
        assertTrue(response.contains("Connection: close\r\n"), response);
        assertEquals(BODY, bodyOf(response));
    }

    @Test
    public void test_head_answers_the_headers_without_the_body() throws IOException {
        started("");

        final var response = exchange("HEAD /metrics HTTP/1.0\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
        assertTrue(response.contains("Content-Length: " + BODY.length() + "\r\n"), response);
        assertEquals("", bodyOf(response));
    }

    @Test
    public void test_another_path_is_not_found_and_another_method_is_not_allowed() throws IOException {
        started("");

        assertTrue(get("/").startsWith("HTTP/1.1 404 Not Found\r\n"));
        assertTrue(get("/metricsx").startsWith("HTTP/1.1 404 Not Found\r\n"));
        final var post = exchange("POST /metrics HTTP/1.1\r\nContent-Length: 0\r\n\r\n");
        assertTrue(post.startsWith("HTTP/1.1 405 Method Not Allowed\r\n"), post);
        assertTrue(post.contains("Allow: GET, HEAD\r\n"), post);
    }

    @Test
    public void test_a_token_is_required_once_configured() throws IOException {
        started("s3cret");

        final var missing = get("/metrics");
        assertTrue(missing.startsWith("HTTP/1.1 401 Unauthorized\r\n"), missing);
        assertTrue(missing.contains("WWW-Authenticate: Bearer\r\n"), missing);
        assertFalse(missing.contains(BODY));
        assertTrue(get("/metrics", "Authorization: Bearer wrong\r\n").startsWith("HTTP/1.1 401"));
        assertTrue(get("/metrics", "Authorization: Bearer s3cretx\r\n").startsWith("HTTP/1.1 401"));
        assertTrue(get("/metrics", "Authorization: Basic s3cret\r\n").startsWith("HTTP/1.1 401"));
        assertTrue(get("/metrics", "Authorization: Bearer s3cret\r\n").startsWith("HTTP/1.1 200 OK\r\n"));
    }

    @Test
    public void test_a_non_ascii_token_matches_its_utf8_bytes_only() throws IOException {
        started("s3cret-tök");
        final var request = "GET /metrics HTTP/1.1\r\nAuthorization: Bearer s3cret-tök\r\n\r\n";

        assertTrue(send(request.getBytes(StandardCharsets.UTF_8)).startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(send(request.getBytes(StandardCharsets.ISO_8859_1)).startsWith("HTTP/1.1 401"));
    }

    @Test
    public void test_an_oversized_head_and_a_malformed_one_are_refused() throws IOException {
        started("");

        final var oversized = get("/metrics", "X-Pad: " + "a".repeat(9000) + "\r\n");
        assertTrue(oversized.startsWith("HTTP/1.1 431 Request Header Fields Too Large\r\n"), oversized);
        assertTrue(exchange("nonsense\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request\r\n"));
    }

    @Test
    public void test_a_silent_client_is_closed_at_the_deadline() throws IOException {
        started("");

        try (var socket = new Socket(LOOPBACK, server.boundPort())) {
            socket.setSoTimeout(10_000);
            final var started = System.nanoTime();
            assertEquals(-1, socket.getInputStream().read());
            final var waitedMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(waitedMs >= READ_TIMEOUT_MS - 200 && waitedMs < 8000, "waited " + waitedMs + " ms");
        }
    }

    @Test
    public void test_scrapes_past_the_concurrency_cap_are_refused() throws Exception {
        started("");
        for (var i = 0; i < 4; i++) {
            final var socket = new Socket(LOOPBACK, server.boundPort());
            socket.getOutputStream().write("GET /met".getBytes(StandardCharsets.ISO_8859_1));
            held.add(socket);
        }
        Thread.sleep(200);

        final var refused = get("/metrics");

        assertTrue(refused.startsWith("HTTP/1.1 503 Service Unavailable\r\n"), refused);
    }

    @Test
    public void test_a_failing_exposition_answers_500() throws IOException {
        started("", () -> {
            throw new IllegalStateException("broken");
        });

        assertTrue(get("/metrics").startsWith("HTTP/1.1 500 Internal Server Error\r\n"));
    }

    @Test
    public void test_stop_closes_the_port_and_a_second_start_is_refused() throws IOException {
        started("");
        final var port = server.boundPort();
        assertThrows(IllegalStateException.class, () -> server.start(0, LOOPBACK, ""));

        server.stop();

        assertEquals(-1, server.boundPort());
        assertThrows(ConnectException.class, () -> new Socket(LOOPBACK, port).close());
    }

    @Test
    public void test_a_taken_port_fails_the_start_naming_metrics_port() throws IOException {
        try (var taken = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            server = new MetricsServer(() -> BODY, READ_TIMEOUT_MS);

            final var failure = assertThrows(IOException.class, () -> server.start(taken.getLocalPort(), LOOPBACK, ""));

            assertTrue(failure.getMessage().contains("metricsPort"), failure.getMessage());
            assertEquals(-1, server.boundPort());
        }
    }

    @Test
    public void test_a_blank_bind_address_listens_on_every_interface() throws IOException {
        server = new MetricsServer(() -> BODY, READ_TIMEOUT_MS);
        server.start(0, "", "");

        assertTrue(get("/metrics").startsWith("HTTP/1.1 200 OK\r\n"));
    }
}
