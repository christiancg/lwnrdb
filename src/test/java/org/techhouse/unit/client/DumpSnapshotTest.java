package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ClientException;
import org.techhouse.client.ClientOptions;
import org.techhouse.client.Connection;
import org.techhouse.client.Dump;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.unit.client.support.FakeLineServer;
import org.techhouse.unit.client.support.FakeSource;

public class DumpSnapshotTest {
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private FakeLineServer server;
    private FakeSource source;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
        source = new FakeSource().database("shop", "orders", "leads").documents("shop", "orders", 5, 10)
                .documents("shop", "leads", 2, 10);
        source.serveOn(server);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private int dump() throws IOException {
        final var options = ClientOptions.parse(new String[]{"--dump"}, Map.of());
        try (var connection = server.connect()) {
            return new Dump(connection, options, stdout, new PrintStream(stderr, true, StandardCharsets.UTF_8)).run();
        }
    }

    private List<JsonObject> requestsOf(String type) {
        return server.requests().stream().filter(request -> type.equals(Connection.stringOf(request, "type"))).toList();
    }

    private List<String> types() {
        return server.requests().stream().map(request -> Connection.stringOf(request, "type")).toList();
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    private String emitted() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void test_one_snapshot_per_database_names_its_collections_and_is_released_after_its_documents()
            throws IOException {
        assertEquals(ClientException.OK, dump());

        final var starts = requestsOf("START_SNAPSHOT");
        assertEquals(1, starts.size());
        assertEquals(List.of("orders", "leads"), starts.getFirst().get("collectionNames").asJsonArray().asList()
                .stream().map(name -> name.asJsonString().getValue()).toList());
        final var release = requestsOf("RELEASE_SNAPSHOT").getFirst();
        assertEquals(FakeSource.SNAPSHOT_ID, Connection.stringOf(release, "snapshotId"));
        final var types = types();
        assertTrue(types.lastIndexOf("READ_SNAPSHOT") < types.indexOf("RELEASE_SNAPSHOT"), types.toString());
        assertTrue(requestsOf("READ_SNAPSHOT").stream()
                .allMatch(read -> FakeSource.SNAPSHOT_ID.equals(Connection.stringOf(read, "snapshotId"))));
    }

    @Test
    public void test_a_lock_timeout_on_start_is_retried() throws IOException {
        final var attempts = new AtomicInteger();
        server.respond("START_SNAPSHOT",
                request -> attempts.incrementAndGet() < 3
                        ? FakeLineServer.error("START_SNAPSHOT", "ERROR", "409-5")
                        : source.startSnapshot(request));

        assertEquals(ClientException.OK, dump());

        assertEquals(3, attempts.get());
        assertTrue(err().contains("shop.orders: 5 documents"), err());
    }

    @Test
    public void test_a_start_refused_past_its_retries_fails_the_dump() {
        server.respond("START_SNAPSHOT", ignored -> FakeLineServer.error("START_SNAPSHOT", "ERROR", "409-5"));

        final var failure = assertThrows(ClientException.class, this::dump);

        assertEquals(ClientException.FAILED, failure.exitCode());
        assertTrue(failure.getMessage().contains("START_SNAPSHOT failed"), failure.getMessage());
    }

    @Test
    public void test_an_aborted_snapshot_fails_the_dump_with_its_reason_and_is_released() {
        server.respond("READ_SNAPSHOT", ignored -> FakeLineServer.error("READ_SNAPSHOT", "ERROR", "409-17"));

        final var failure = assertThrows(ClientException.class, this::dump);

        assertEquals(ClientException.FAILED, failure.exitCode());
        assertTrue(failure.getMessage().contains("the snapshot of shop was lost"), failure.getMessage());
        assertTrue(failure.getMessage().contains("the dump is incomplete"), failure.getMessage());
        assertEquals(1, requestsOf("RELEASE_SNAPSHOT").size());
    }

    @Test
    public void test_an_expired_snapshot_fails_the_dump() {
        server.respond("READ_SNAPSHOT", ignored -> FakeLineServer.error("READ_SNAPSHOT", "NOT_FOUND", "404-13"));

        final var failure = assertThrows(ClientException.class, this::dump);

        assertTrue(failure.getMessage().contains("the snapshot of shop was lost"), failure.getMessage());
    }

    @Test
    public void test_a_collection_the_snapshot_did_not_capture_is_noted_and_left_out() throws IOException {
        server.respond("START_SNAPSHOT", ignored -> FakeLineServer.ok("START_SNAPSHOT",
                "\"snapshotId\":\"" + FakeSource.SNAPSHOT_ID + "\",\"collectionNames\":[\"orders\"]"));

        assertEquals(ClientException.OK, dump());

        assertTrue(err().contains("note: shop.leads was not dumped"), err());
        assertFalse(emitted().contains("\"collectionName\":\"leads\""), emitted());
        assertTrue(requestsOf("GET_SCHEMA").stream()
                .noneMatch(request -> "leads".equals(Connection.stringOf(request, "collectionName"))));
    }

    @Test
    public void test_an_empty_page_that_is_not_done_continues_after_its_last_id() throws IOException {
        final var calls = new AtomicInteger();
        server.respond("READ_SNAPSHOT",
                request -> calls.incrementAndGet() == 1
                        ? FakeLineServer.ok("READ_SNAPSHOT", "\"results\":[],\"lastId\":\"orders-0\",\"done\":false")
                        : source.page(request));

        assertEquals(ClientException.OK, dump());

        final var second = requestsOf("READ_SNAPSHOT").get(1);
        assertEquals("orders-0", Connection.stringOf(second, "afterId"));
        assertEquals(Dump.PAGE_SIZE, second.get("limit").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_a_page_that_is_not_done_without_a_last_id_is_an_unexpected_reply() {
        server.respond("READ_SNAPSHOT",
                ignored -> FakeLineServer.ok("READ_SNAPSHOT", "\"results\":[],\"lastId\":null,\"done\":false"));

        final var failure = assertThrows(ClientException.class, this::dump);

        assertTrue(failure.getMessage().contains("unexpected lastId"), failure.getMessage());
    }

    @Test
    public void test_a_database_without_collections_opens_no_snapshot() throws IOException {
        new FakeSource().database("empty_db").serveOn(server);

        assertEquals(ClientException.OK, dump());

        assertTrue(requestsOf("START_SNAPSHOT").isEmpty());
        assertTrue(emitted().contains("\"type\":\"CREATE_DATABASE\",\"databaseName\":\"empty_db\""), emitted());
    }
}
