package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ClientException;
import org.techhouse.client.Restore;
import org.techhouse.unit.client.support.FakeLineServer;

public class RestoreTest {
    private static final String CREATE_SHOP = "{\"type\":\"CREATE_DATABASE\",\"databaseName\":\"shop\"}";
    private static final String CREATE_ORDERS = "{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"shop\","
            + "\"collectionName\":\"orders\"}";
    private static final String SAVE_ORDERS = "{\"type\":\"BULK_SAVE\",\"databaseName\":\"shop\","
            + "\"collectionName\":\"orders\",\"objects\":[{\"_id\":\"o1\"}]}";
    private FakeLineServer server;
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private int restore(InputStream in) throws IOException {
        try (var connection = server.connect()) {
            return new Restore(connection, in, new PrintStream(errors, true, StandardCharsets.UTF_8)).run();
        }
    }

    private static InputStream plain(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] gzip(String text) throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private String stderr() {
        return errors.toString(StandardCharsets.UTF_8);
    }

    private List<String> replayedTypes() {
        final var types = server.types();
        assertEquals("LIST_DATABASES", types.getFirst());
        return types.subList(1, types.size());
    }

    private void serveExistingDatabases(String... names) {
        server.reply("LIST_DATABASES",
                FakeLineServer.ok("LIST_DATABASES", "\"databases\":[\"" + String.join("\",\"", names) + "\"]"));
    }

    @Test
    public void test_plain_input_sends_every_line() throws IOException {
        assertEquals(ClientException.OK, restore(plain(CREATE_SHOP + "\n" + CREATE_ORDERS + "\n" + SAVE_ORDERS)));

        assertEquals(List.of(CREATE_SHOP, CREATE_ORDERS, SAVE_ORDERS), server.lines().subList(1, 4));
        assertTrue(stderr().contains("restored 3 requests"), stderr());
    }

    @Test
    public void test_gzip_input_detected() throws IOException {
        assertEquals(ClientException.OK, restore(new ByteArrayInputStream(gzip(CREATE_SHOP + "\n" + CREATE_ORDERS))));

        assertEquals(List.of("CREATE_DATABASE", "CREATE_COLLECTION", "CLOSE_CONNECTION"), replayedTypes());
    }

    @Test
    public void test_empty_input_restores_nothing() throws IOException {
        assertEquals(ClientException.OK, restore(plain("")));

        assertEquals(List.of("CLOSE_CONNECTION"), replayedTypes());
        assertTrue(stderr().contains("restored 0 requests"), stderr());
    }

    @Test
    public void test_blank_lines_skipped() throws IOException {
        assertEquals(ClientException.OK, restore(plain("\n" + CREATE_SHOP + "\n\n  \n" + CREATE_ORDERS + "\n\n")));

        assertEquals(List.of("CREATE_DATABASE", "CREATE_COLLECTION", "CLOSE_CONNECTION"), replayedTypes());
    }

    @Test
    public void test_stops_at_first_error_with_line_number() throws IOException {
        server.reply("CREATE_COLLECTION", FakeLineServer.error("CREATE_COLLECTION", "ERROR", "400-1"));

        assertEquals(ClientException.FAILED, restore(plain(CREATE_SHOP + "\n\n" + CREATE_ORDERS + "\n" + SAVE_ORDERS)));

        assertEquals(List.of("CREATE_DATABASE", "CREATE_COLLECTION", "CLOSE_CONNECTION"), replayedTypes());
        assertTrue(stderr().contains("line 3: "), stderr());
        assertTrue(stderr().contains("restored 1 requests"), stderr());
    }

    @Test
    public void test_progress_goes_to_stderr_not_stdout() throws IOException {
        final var original = System.out;
        final var stdout = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            final var lines = (CREATE_ORDERS + "\n").repeat(Restore.PROGRESS_EVERY + 1);
            assertEquals(ClientException.OK, restore(plain(lines)));
        } finally {
            System.setOut(original);
        }
        assertEquals(0, stdout.size());
        assertTrue(stderr().contains("restored " + Restore.PROGRESS_EVERY + " requests"), stderr());
        assertTrue(stderr().contains("restored " + (Restore.PROGRESS_EVERY + 1) + " requests"), stderr());
    }

    @Test
    public void test_existing_database_is_accepted_and_restore_continues() throws IOException {
        server.reply("CREATE_DATABASE", FakeLineServer.error("CREATE_DATABASE", "ERROR", "409-2"));

        assertEquals(ClientException.OK, restore(plain(CREATE_SHOP + "\n" + CREATE_ORDERS)));

        assertEquals(List.of("CREATE_DATABASE", "CREATE_COLLECTION", "CLOSE_CONNECTION"), replayedTypes());
    }

    @Test
    public void test_409_2_on_a_request_other_than_create_database_still_stops() throws IOException {
        server.reply("CREATE_COLLECTION", FakeLineServer.error("CREATE_COLLECTION", "ERROR", "409-2"));

        assertEquals(ClientException.FAILED, restore(plain(CREATE_ORDERS + "\n" + SAVE_ORDERS)));

        assertEquals(List.of("CREATE_COLLECTION", "CLOSE_CONNECTION"), replayedTypes());
    }

    @Test
    public void test_create_database_for_an_existing_database_is_skipped() throws IOException {
        serveExistingDatabases("crm", "shop");

        assertEquals(ClientException.OK, restore(plain(CREATE_SHOP + "\n" + CREATE_ORDERS)));

        assertEquals(List.of("CREATE_COLLECTION", "CLOSE_CONNECTION"), replayedTypes());
        assertTrue(stderr().contains("restored 1 requests, skipped 1 existing databases"), stderr());
    }

    @Test
    public void test_create_database_for_a_new_database_is_sent() throws IOException {
        serveExistingDatabases("crm");

        assertEquals(ClientException.OK, restore(plain(CREATE_SHOP + "\n" + CREATE_ORDERS)));

        assertEquals(List.of("CREATE_DATABASE", "CREATE_COLLECTION", "CLOSE_CONNECTION"), replayedTypes());
        assertFalse(stderr().contains("skipped"), stderr());
    }

    @Test
    public void test_skipped_line_keeps_the_line_numbers() throws IOException {
        serveExistingDatabases("shop");
        server.reply("BULK_SAVE", FakeLineServer.error("BULK_SAVE", "ERROR", "400-7"));

        assertEquals(ClientException.FAILED, restore(plain(CREATE_SHOP + "\n" + CREATE_ORDERS + "\n" + SAVE_ORDERS)));

        assertTrue(stderr().contains("line 3: "), stderr());
        assertTrue(stderr().contains("restored 1 requests, skipped 1 existing databases"), stderr());
    }

    @Test
    public void test_a_create_database_line_that_is_not_json_is_sent_as_is() throws IOException {
        serveExistingDatabases("shop");

        assertEquals(ClientException.FAILED, restore(plain("{\"type\":\"CREATE_DATABASE\",broken")));

        assertTrue(server.lines().contains("{\"type\":\"CREATE_DATABASE\",broken"));
    }

    @Test
    public void test_a_refused_database_listing_fails_the_restore() {
        server.reply("LIST_DATABASES", FakeLineServer.error("LIST_DATABASES", "ERROR", "500-1"));

        final var error = assertThrows(ClientException.class, () -> restore(plain(CREATE_SHOP)));

        assertEquals(ClientException.FAILED, error.exitCode());
        assertEquals(List.of("LIST_DATABASES", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_truncated_gzip_reports_the_requests_restored_and_fails() throws IOException {
        final var full = gzip(CREATE_SHOP + "\n" + CREATE_ORDERS + "\n" + SAVE_ORDERS + "\n");
        final var truncated = Arrays.copyOf(full, full.length - 12);

        assertEquals(ClientException.FAILED, restore(new ByteArrayInputStream(truncated)));

        assertTrue(stderr().contains("cannot read the backup"), stderr());
    }

    @Test
    public void test_a_server_that_disappears_is_unreachable_with_the_line_number() {
        server.reply("CREATE_COLLECTION", FakeLineServer.HANG_UP);

        final var error = assertThrows(ClientException.class, () -> restore(plain(CREATE_SHOP + "\n" + CREATE_ORDERS)));

        assertEquals(ClientException.UNREACHABLE, error.exitCode());
        assertTrue(error.getMessage().startsWith("line 2: "), error.getMessage());
        assertTrue(stderr().contains("restored 1 requests"), stderr());
    }
}
