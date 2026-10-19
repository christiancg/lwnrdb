package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ClientException;
import org.techhouse.client.ClientOptions;
import org.techhouse.client.Connection;
import org.techhouse.client.Requests;
import org.techhouse.unit.client.support.FakeLineServer;

public class ConnectionTest {
    private FakeLineServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    public void test_send_receives_one_reply_per_request() throws IOException {
        server.reply("LIST_DATABASES", FakeLineServer.ok("LIST_DATABASES", "\"databases\":[\"a\"]"));
        try (var connection = server.connect()) {
            final var first = connection.send(Requests.listDatabases());
            final var second = connection.sendLine("{\"type\":\"LIST_DATABASES\"}");

            assertTrue(Connection.isOk(first));
            assertEquals(first, second);
        }
        assertEquals(List.of("LIST_DATABASES", "LIST_DATABASES", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_a_line_with_newlines_is_sent_as_one_request() throws IOException {
        try (var connection = server.connect()) {
            connection.sendLine("{\"type\":\n\"LIST_DATABASES\"\r}");
        }
        assertEquals("{\"type\": \"LIST_DATABASES\" }", server.lines().getFirst());
    }

    @Test
    public void test_non_json_reply_becomes_an_error_object() throws IOException {
        try (var connection = server.connect()) {
            final var reply = connection.sendLine("not json");

            assertFalse(Connection.isOk(reply));
            assertEquals("ERROR", Connection.stringOf(reply, Connection.STATUS));
            assertEquals("The command is not valid", Connection.stringOf(reply, Connection.MESSAGE));
        }
    }

    @Test
    public void test_crlf_reply_is_parsed() throws IOException {
        server.reply("LIST_DATABASES", FakeLineServer.ok("LIST_DATABASES") + "\r");
        try (var connection = server.connect()) {
            assertTrue(Connection.isOk(connection.send(Requests.listDatabases())));
        }
    }

    @Test
    public void test_retries_503_16_then_succeeds() throws IOException {
        server.reply("SAVE", FakeLineServer.error("SAVE", "ERROR", "503-16"),
                FakeLineServer.error("SAVE", "ERROR", "503-16"), FakeLineServer.ok("SAVE"));
        try (var connection = server.connect()) {
            assertTrue(Connection.isOk(connection.sendLine("{\"type\":\"SAVE\"}")));
        }
        assertEquals(3, server.types().stream().filter("SAVE"::equals).count());
    }

    @Test
    public void test_gives_up_after_five_attempts() throws IOException {
        server.reply("SAVE", FakeLineServer.error("SAVE", "ERROR", "503-2"));
        try (var connection = server.connect()) {
            final var reply = connection.sendLine("{\"type\":\"SAVE\"}");

            assertEquals("503-2", Connection.errorCodeOf(reply));
        }
        assertEquals(Connection.MAX_ATTEMPTS, server.types().stream().filter("SAVE"::equals).count());
    }

    @Test
    public void test_non_retryable_is_not_retried() throws IOException {
        server.reply("SAVE", FakeLineServer.error("SAVE", "ERROR", "503-8"));
        try (var connection = server.connect()) {
            assertEquals("503-8", Connection.errorCodeOf(connection.sendLine("{\"type\":\"SAVE\"}")));
        }
        assertEquals(1, server.types().stream().filter("SAVE"::equals).count());
    }

    @Test
    public void test_close_sends_close_connection() throws IOException {
        server.connect().close();
        assertEquals(List.of("CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_close_after_the_server_hung_up_does_not_throw() throws IOException {
        server.reply("CLOSE_CONNECTION", FakeLineServer.HANG_UP);
        server.connect().close();
        assertEquals(List.of("CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_a_hang_up_is_unreachable() throws IOException {
        server.reply("SAVE", FakeLineServer.HANG_UP);
        try (var connection = server.connect()) {
            final var error = assertThrows(ClientException.class, () -> connection.sendLine("{\"type\":\"SAVE\"}"));
            assertEquals(ClientException.UNREACHABLE, error.exitCode());
        }
    }

    @Test
    public void test_send_ok_names_the_refused_request() throws IOException {
        server.reply("LIST_DATABASES", FakeLineServer.error("LIST_DATABASES", "FORBIDDEN", "403-1"));
        try (var connection = server.connect()) {
            final var error = assertThrows(ClientException.class, () -> connection.sendOk(Requests.listDatabases()));
            assertEquals(ClientException.FAILED, error.exitCode());
            assertTrue(error.getMessage().startsWith("LIST_DATABASES failed:"));
        }
    }

    @Test
    public void test_open_and_authenticate() {
        final var options = ClientOptions.parse(new String[]{"--shell", "--port", String.valueOf(server.port())},
                Map.of());
        try (var connection = Connection.open(options)) {
            connection.authenticate("admin", "secret");
        }
        final var authenticate = server.requests().getFirst();
        assertEquals("admin", Connection.stringOf(authenticate, "username"));
        assertEquals("secret", Connection.stringOf(authenticate, "password"));
    }

    @Test
    public void test_a_refused_authentication_is_unreachable() {
        server.reply("AUTHENTICATE", FakeLineServer.error("AUTHENTICATE", "ERROR", "401-3"));
        final var options = ClientOptions.parse(new String[]{"--shell", "--port", String.valueOf(server.port())},
                Map.of());
        try (var connection = Connection.open(options)) {
            final var error = assertThrows(ClientException.class, () -> connection.authenticate("admin", "bad"));
            assertEquals(ClientException.UNREACHABLE, error.exitCode());
        }
    }

    @Test
    public void test_open_on_a_closed_port_is_unreachable() throws IOException {
        final int closedPort;
        try (var probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        final var options = ClientOptions.parse(new String[]{"--shell", "--port", String.valueOf(closedPort)},
                Map.of());
        final var error = assertThrows(ClientException.class, () -> Connection.open(options).close());
        assertEquals(ClientException.UNREACHABLE, error.exitCode());
    }

    @Test
    public void test_a_failed_tls_handshake_is_unreachable() throws Exception {
        assertHandshakeFails("--tls", "--insecure");
        assertHandshakeFails("--tls");
    }

    private static void assertHandshakeFails(String... tlsFlags) throws Exception {
        try (var closing = new ServerSocket(0)) {
            final var acceptor = Thread.ofVirtual().start(() -> {
                try {
                    closing.accept().close();
                } catch (IOException ignored) {
                }
            });
            final var args = new ArrayList<>(List.of("--shell", "--port", String.valueOf(closing.getLocalPort())));
            args.addAll(List.of(tlsFlags));
            final var options = ClientOptions.parse(args.toArray(String[]::new), Map.of());
            final var error = assertThrows(ClientException.class, () -> Connection.open(options).close());
            assertEquals(ClientException.UNREACHABLE, error.exitCode());
            acceptor.join();
        }
    }
}
