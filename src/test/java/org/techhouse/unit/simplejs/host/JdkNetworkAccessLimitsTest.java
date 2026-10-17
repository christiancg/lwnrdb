package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.simplejs.host.FetchRequest;
import org.techhouse.simplejs.host.JdkNetworkAccess;
import org.techhouse.simplejs.host.JdkNetworkAccess.Kind;
import org.techhouse.simplejs.host.JdkNetworkAccess.NetworkException;
import org.techhouse.simplejs.host.PinnedResolution;

public class JdkNetworkAccessLimitsTest {
    private static final int CAP = 1024;
    private static final String REBIND_HOST = "rebind.lwnrdb.invalid";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger targetHits = new AtomicInteger();
    private final CountDownLatch endlessClosed = new CountDownLatch(1);
    private final JdkNetworkAccess network = new JdkNetworkAccess();

    @BeforeEach
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixed", exchange -> {
            hits.incrementAndGet();
            final var size = Integer.parseInt(exchange.getRequestURI().getQuery());
            final var body = "x".repeat(size).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/accented", exchange -> {
            final var body = "é".repeat(600).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/endless", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            final var chunk = new byte[4096];
            try (var out = exchange.getResponseBody()) {
                while (!Thread.currentThread().isInterrupted()) {
                    out.write(chunk);
                    out.flush();
                }
            } catch (IOException closedByClient) {
                endlessClosed.countDown();
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    public void tearDown() {
        server.stop(0);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port() + path;
    }

    private static FetchRequest request(String url, long maxBytes, boolean privateAllowed) {
        return new FetchRequest("GET", url, Map.of(), null, 2000, maxBytes, privateAllowed);
    }

    private Kind failureKind(FetchRequest request) {
        return assertThrows(NetworkException.class, () -> network.fetch(request)).kind();
    }

    @Test
    public void test_loopback_is_blocked_unless_private_allowed() {
        assertEquals(Kind.BLOCKED, failureKind(request(url("/fixed?3"), -1, false)));
        assertEquals(0, hits.get());
        assertEquals("xxx", network.fetch(request(url("/fixed?3"), -1, true)).bodyText());
        assertEquals(1, hits.get());
    }

    @Test
    public void test_localhost_name_is_blocked() {
        assertEquals(Kind.BLOCKED, failureKind(request("http://localhost:" + port() + "/fixed?3", -1, false)));
        assertEquals(Kind.BLOCKED, failureKind(request("http://[::1]:" + port() + "/fixed?3", -1, false)));
        assertEquals(0, hits.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.com/x", "jar:file:/x!/y", "http:///nohost",
            "not a url", "//example.com/x"})
    public void test_non_http_scheme_is_blocked(String target) {
        assertEquals(Kind.BLOCKED, failureKind(request(target, -1, true)));
    }

    @Test
    public void test_response_over_cap_is_aborted_without_reading_it_all() throws InterruptedException {
        final var kind = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> failureKind(request(url("/endless"), CAP, true)));
        assertEquals(Kind.TOO_LARGE, kind);
        assertTrue(endlessClosed.await(10, TimeUnit.SECONDS), "the server should see the connection closed");
    }

    @Test
    public void test_content_length_over_cap_is_rejected_early() {
        assertEquals(Kind.TOO_LARGE, failureKind(request(url("/fixed?" + (CAP + 1)), CAP, true)));
    }

    @Test
    public void test_body_exactly_at_cap_is_accepted() {
        assertEquals(CAP, network.fetch(request(url("/fixed?" + CAP), CAP, true)).bodyText().length());
    }

    @Test
    public void test_multibyte_body_counts_bytes() {
        assertEquals(Kind.TOO_LARGE, failureKind(request(url("/accented"), 1000, true)));
        assertEquals(600, network.fetch(request(url("/accented"), 1200, true)).bodyText().length());
    }

    @Test
    public void test_redirect_is_not_followed() {
        final var response = network.fetch(request(url("/redirect"), -1, true));
        assertEquals(302, response.status());
        assertEquals(0, targetHits.get());
    }

    @Test
    public void test_connect_refused_message_is_generic() {
        final var failure = assertThrows(NetworkException.class,
                () -> network.fetch(request("http://127.0.0.1:1/x", -1, true)));
        assertEquals(Kind.FAILED, failure.kind());
        assertFalse(failure.getMessage().toLowerCase(Locale.ROOT).contains("refused"), failure.getMessage());
    }

    @Test
    public void test_unresolvable_host_is_a_generic_failure() {
        assertEquals(Kind.FAILED, failureKind(request("http://no-such-host.lwnrdb.invalid/", -1, true)));
    }

    @Test
    public void test_pin_is_released_after_failure() {
        assertEquals(Kind.FAILED, failureKind(request("http://localhost:1/x", -1, true)));
        assertNull(PinnedResolution.lookup("localhost"));
    }

    @Test
    public void test_second_lookup_cannot_change_the_connected_address() {
        try (var _ = PinnedResolution.pin(REBIND_HOST, List.of(InetAddress.ofLiteral("127.0.0.1")))) {
            final var target = "http://" + REBIND_HOST + ":" + port() + "/fixed?2";
            assertEquals(Kind.BLOCKED, failureKind(request(target, -1, false)));
            assertEquals("xx", network.fetch(request(target, -1, true)).bodyText());
            assertEquals(1, hits.get());
        }
        assertNull(PinnedResolution.lookup(REBIND_HOST));
    }

    @Test
    public void test_charset_is_taken_from_the_content_type() {
        assertEquals(StandardCharsets.ISO_8859_1, JdkNetworkAccess.charsetOf("text/plain; charset=\"ISO-8859-1\""));
        assertEquals(StandardCharsets.UTF_16, JdkNetworkAccess.charsetOf("text/plain; Charset=UTF-16; x=1"));
        assertEquals(StandardCharsets.UTF_8, JdkNetworkAccess.charsetOf("text/plain; charset=no-such-charset"));
        assertEquals(StandardCharsets.UTF_8, JdkNetworkAccess.charsetOf("application/json"));
    }
}
