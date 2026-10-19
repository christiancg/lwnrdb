package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ClientException;
import org.techhouse.client.ClientMain;
import org.techhouse.client.ClientOptions;
import org.techhouse.unit.client.support.FakeLineServer;
import org.techhouse.unit.client.support.FakeSource;

public class ClientMainTest {
    private static final String LIST = "{\"type\":\"LIST_DATABASES\"}";
    private FakeLineServer server;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private int run(String stdin, Map<String, String> env, String... args) {
        return ClientMain.run(args, env, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    private int runAgainstServer(String stdin, String... args) {
        final var all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--port", String.valueOf(server.port()), "--password", "pw"));
        return run(stdin, Map.of(), all.toArray(String[]::new));
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void test_flag_first_argument_is_a_client_invocation() {
        assertTrue(ClientMain.isClientInvocation(new String[]{"--shell"}));
        assertTrue(ClientMain.isClientInvocation(new String[]{"--bogus", "8989"}));
    }

    @Test
    public void test_port_or_no_argument_is_not() {
        assertFalse(ClientMain.isClientInvocation(new String[]{"8989"}));
        assertFalse(ClientMain.isClientInvocation(new String[]{}));
    }

    @Test
    public void test_usage_error_returns_2_and_prints_usage_to_stderr() {
        assertEquals(ClientException.USAGE, run("", Map.of(), "--bogus"));

        assertTrue(err().contains("unknown argument '--bogus'"), err());
        assertTrue(err().contains(ClientMain.USAGE), err());
        assertEquals("", out());
    }

    @Test
    public void test_missing_password_is_a_usage_error() {
        assertEquals(ClientException.USAGE, run("", Map.of(), "--restore", "--port", String.valueOf(server.port())));

        assertTrue(err().contains(ClientOptions.PASSWORD_ENV), err());
        assertTrue(server.types().isEmpty());
    }

    @Test
    public void test_env_password_authenticates() {
        assertEquals(ClientException.OK, run("", Map.of(ClientOptions.PASSWORD_ENV, "fromEnv"), "--exec", LIST,
                "--port", String.valueOf(server.port())));

        assertEquals("fromEnv", server.requests().getFirst().get("password").asJsonString().getValue());
    }

    @Test
    public void test_connection_refused_returns_3() throws IOException {
        final int closedPort;
        try (var probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        assertEquals(ClientException.UNREACHABLE,
                run("", Map.of(), "--exec", LIST, "--port", String.valueOf(closedPort), "--password", "pw"));
        assertTrue(err().startsWith("cannot connect to 127.0.0.1:" + closedPort), err());
    }

    @Test
    public void test_wrong_password_returns_3() {
        server.reply("AUTHENTICATE", FakeLineServer.error("AUTHENTICATE", "ERROR", "401-3"));

        assertEquals(ClientException.UNREACHABLE, runAgainstServer("", "--exec", LIST));

        assertTrue(err().startsWith("authentication refused"), err());
        assertEquals(List.of("AUTHENTICATE", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_exec_ok_returns_0() {
        assertEquals(ClientException.OK, runAgainstServer("", "--exec", LIST, "--exec", LIST));

        assertEquals(2, out().lines().count());
        assertEquals(List.of("AUTHENTICATE", "LIST_DATABASES", "LIST_DATABASES", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_exec_reads_commands_from_stdin() {
        assertEquals(ClientException.OK, runAgainstServer(LIST + "\n\n" + LIST + "\n", "--exec"));

        assertEquals(2, out().lines().count());
    }

    @Test
    public void test_exec_non_ok_returns_1() {
        server.reply("FIND_BY_ID", FakeLineServer.error("FIND_BY_ID", "NOT_FOUND", "404-2"));

        assertEquals(ClientException.FAILED,
                runAgainstServer("", "--exec", "{\"type\":\"FIND_BY_ID\"}", "--exec", LIST));

        assertEquals(1, out().lines().count());
        assertTrue(out().contains("404-2"), out());
    }

    @Test
    public void test_shell_runs_until_exit() {
        assertEquals(ClientException.OK, runAgainstServer(LIST + "\nexit\n", "--shell"));

        assertTrue(out().contains("\"type\": \"LIST_DATABASES\""), out());
    }

    @Test
    public void test_dump_writes_to_stdout_and_restore_into_an_existing_database_sends_no_create_database() {
        new FakeSource().database("shop", "orders").documents("shop", "orders", 3, 5).serveOn(server);

        assertEquals(ClientException.OK, runAgainstServer("", "--dump"));
        final var dumped = out();
        assertEquals(3, dumped.lines().count());

        stdout.reset();
        final var requestsBeforeRestore = server.types().size();
        assertEquals(ClientException.OK, runAgainstServer(dumped, "--restore"));
        assertEquals("", out());
        assertTrue(err().contains("restored 2 requests, skipped 1 existing databases"), err());
        final var restoreTypes = server.types().subList(requestsBeforeRestore, server.types().size());
        assertFalse(restoreTypes.contains("CREATE_DATABASE"), restoreTypes.toString());
    }

    @Test
    public void test_a_failed_dump_returns_1() {
        new FakeSource().database("shop", "orders").serveOn(server);
        server.reply("GET_DATABASE_STATS", FakeLineServer.error("GET_DATABASE_STATS", "FORBIDDEN", "403-1"));

        assertEquals(ClientException.FAILED, runAgainstServer("", "--dump"));

        assertTrue(err().startsWith("GET_DATABASE_STATS failed:"), err());
        assertEquals("", out());
    }
}
