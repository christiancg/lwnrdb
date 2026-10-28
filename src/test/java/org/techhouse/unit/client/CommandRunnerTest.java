package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ClientException;
import org.techhouse.client.CommandRunner;
import org.techhouse.unit.client.support.FakeLineServer;

public class CommandRunnerTest {
    private static final String CREATE_SHOP = "{\"type\":\"CREATE_DATABASE\",\"databaseName\":\"shop\"}";
    private FakeLineServer server;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private String printed() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void test_exec_prints_compact_replies() throws IOException {
        try (var connection = server.connect()) {
            final var result = new CommandRunner(connection, out, true, false)
                    .run(List.of("{\"type\":\"LIST_DATABASES\"}", "  ", "{\"type\":\"LIST_DATABASES\"}").iterator());

            assertEquals(ClientException.OK, result);
        }
        final var lines = printed().lines().toList();
        assertEquals(2, lines.size());
        assertEquals(FakeLineServer.ok("LIST_DATABASES"), lines.getFirst());
    }

    @Test
    public void test_quiet_mode_prints_nothing() throws IOException {
        try (var connection = server.connect()) {
            new CommandRunner(connection, out, false, true).run(List.of(CREATE_SHOP).iterator());
        }
        assertEquals("", printed());
    }

    @Test
    public void test_existing_database_fails_when_not_merging() throws IOException {
        server.reply("CREATE_DATABASE", FakeLineServer.error("CREATE_DATABASE", "ERROR", "409-2"));
        try (var connection = server.connect()) {
            final var result = new CommandRunner(connection, out, true, false)
                    .run(List.of(CREATE_SHOP, "{\"type\":\"LIST_DATABASES\"}").iterator());

            assertEquals(ClientException.FAILED, result);
        }
        assertEquals(List.of("CREATE_DATABASE", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_existing_database_succeeds_when_merging() throws IOException {
        server.reply("CREATE_DATABASE", FakeLineServer.error("CREATE_DATABASE", "ERROR", "409-2"));
        try (var connection = server.connect()) {
            final var result = new CommandRunner(connection, out, false, true)
                    .run(List.of(CREATE_SHOP, "{\"type\":\"LIST_DATABASES\"}").iterator());

            assertEquals(ClientException.OK, result);
        }
        assertEquals("", printed());
    }

    @Test
    public void test_quiet_failure_names_the_line() throws IOException {
        server.reply("SAVE", FakeLineServer.error("SAVE", "ERROR", "400-7"));
        try (var connection = server.connect()) {
            final var result = new CommandRunner(connection, out, false, true)
                    .run(List.of("", "{\"type\":\"SAVE\"}").iterator());

            assertEquals(ClientException.FAILED, result);
        }
        assertTrue(printed().startsWith("line 2: "), printed());
    }

    @Test
    public void test_a_lost_connection_names_the_line() throws IOException {
        server.reply("SAVE", FakeLineServer.HANG_UP);
        try (var connection = server.connect()) {
            final var runner = new CommandRunner(connection, out, false, true);
            final var error = assertThrows(ClientException.class,
                    () -> runner.run(List.of(CREATE_SHOP, "{\"type\":\"SAVE\"}").iterator()));

            assertEquals(ClientException.UNREACHABLE, error.exitCode());
            assertTrue(error.getMessage().startsWith("line 2: "), error.getMessage());
        }
    }
}
