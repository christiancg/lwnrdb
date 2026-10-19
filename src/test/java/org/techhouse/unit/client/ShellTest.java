package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ClientException;
import org.techhouse.client.Shell;
import org.techhouse.unit.client.support.FakeLineServer;

public class ShellTest {
    private FakeLineServer server;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private int runShell(String input) throws IOException {
        try (var connection = server.connect()) {
            return new Shell(connection, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(captured, true, StandardCharsets.UTF_8)).run();
        }
    }

    private String printed() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void test_sends_line_and_pretty_prints() throws IOException {
        server.reply("LIST_DATABASES", FakeLineServer.ok("LIST_DATABASES", "\"databases\":[\"shop\"]"));

        assertEquals(ClientException.OK, runShell("{\"type\":\"LIST_DATABASES\"}\nexit\n"));

        assertTrue(printed().startsWith(Shell.PROMPT + "{\n  \"type\": \"LIST_DATABASES\""), printed());
        assertTrue(printed().contains("  \"databases\": [\n    \"shop\"\n  ]"), printed());
    }

    @Test
    public void test_exit_and_quit_end_the_session() throws IOException {
        runShell("exit\n{\"type\":\"LIST_DATABASES\"}\n");
        runShell("  quit  \n{\"type\":\"LIST_DATABASES\"}\n");

        assertEquals(List.of("CLOSE_CONNECTION", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_eof_ends_the_session() throws IOException {
        assertEquals(ClientException.OK, runShell("{\"type\":\"LIST_DATABASES\"}"));

        assertEquals(List.of("LIST_DATABASES", "CLOSE_CONNECTION"), server.types());
        assertTrue(printed().endsWith(Shell.PROMPT), printed());
    }

    @Test
    public void test_blank_line_ignored() throws IOException {
        runShell("\n   \n{\"type\":\"LIST_DATABASES\"}\n");

        assertEquals(List.of("LIST_DATABASES", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_an_error_reply_does_not_end_the_session() throws IOException {
        server.reply("FIND_BY_ID", FakeLineServer.error("FIND_BY_ID", "NOT_FOUND", "404-2"));

        assertEquals(ClientException.OK, runShell("{\"type\":\"FIND_BY_ID\"}\n{\"type\":\"LIST_DATABASES\"}\n"));

        assertEquals(List.of("FIND_BY_ID", "LIST_DATABASES", "CLOSE_CONNECTION"), server.types());
    }
}
