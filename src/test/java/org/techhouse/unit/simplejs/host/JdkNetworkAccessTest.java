package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.FetchRequest;
import org.techhouse.simplejs.host.JdkNetworkAccess;
import org.techhouse.simplejs.host.ResourceLimits;

public class JdkNetworkAccessTest {
    private HttpServer server;
    private String baseUrl;
    private final String[] lastMethod = new String[1];
    private final String[] lastBody = new String[1];

    @BeforeEach
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            lastMethod[0] = exchange.getRequestMethod();
            lastBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final var body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Test", "yes");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            final var body = "nope".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    public void tearDown() {
        server.stop(0);
    }

    // A real GET round-trips status, headers and body
    @Test
    public void test_get_round_trip() {
        final var network = new JdkNetworkAccess();
        final var response = network.fetch(new FetchRequest("GET", baseUrl + "/echo", Map.of(), null, 2000));
        assertEquals(200, response.status());
        final var testHeader = response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("X-Test")).map(Map.Entry::getValue).findFirst();
        assertEquals("yes", testHeader.orElse(null));
        assertTrue(response.bodyText().contains("\"ok\":true"));
    }

    // POST forwards the method and body
    @Test
    public void test_post_forwards_body() {
        final var network = new JdkNetworkAccess();
        network.fetch(new FetchRequest("POST", baseUrl + "/echo", Map.of("X-A", "1"), "payload", 2000));
        assertEquals("POST", lastMethod[0]);
        assertEquals("payload", lastBody[0]);
    }

    // A 404 is reported as a non-OK status, not an exception
    @Test
    public void test_not_found_status() {
        final var network = new JdkNetworkAccess();
        final var response = network.fetch(new FetchRequest("GET", baseUrl + "/missing", Map.of(), null, 2000));
        assertEquals(404, response.status());
        assertEquals("Not Found", response.statusText());
    }

    // A connection to a dead port throws a NetworkException
    @Test
    public void test_connection_failure_throws() {
        final var network = new JdkNetworkAccess();
        assertThrows(JdkNetworkAccess.NetworkException.class,
                () -> network.fetch(new FetchRequest("GET", "http://127.0.0.1:1/x", Map.of(), null, 500)));
    }

    // End-to-end: a script fetches a real endpoint and parses the JSON body
    @Test
    public void test_end_to_end_script() {
        final var engine = new SimpleJs();
        final var limits = new ResourceLimits(-1, 5000, -1, false, true, List.of("127.0.0.1"), -1, 2000);
        final var host = NetworkHostBindings.of(new JdkNetworkAccess(), limits);
        final var source = "const r = await fetch('" + baseUrl + "/echo'); const j = await r.json(); return j.ok;";
        final var result = engine.run(source, host);
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }
}
