package io.github.christiancg.lwnrdb.client;

import static io.github.christiancg.lwnrdb.client.query.Filters.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.error.AuthenticationException;
import io.github.christiancg.lwnrdb.client.error.ConflictException;
import io.github.christiancg.lwnrdb.client.error.UnknownOutcomeException;
import io.github.christiancg.lwnrdb.client.testing.FakeServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

public class LwnrdbClientTest {
    private final Map<String, ObjectNode> canned = new ConcurrentHashMap<>();

    private FakeServer server() throws Exception {
        return new FakeServer((request, session) -> {
            final var type = request.get("type").asString();
            final var reply = canned.get(type);
            session.send(reply != null ? reply.deepCopy() : FakeServer.ok(request));
        });
    }

    private static LwnrdbClient connect(FakeServer server) {
        return LwnrdbClient.connect(
                ClientSettings.builder().host("127.0.0.1", server.port()).credentials("alice", "pw")
                        .requestTimeout(Duration.ofSeconds(5)).shutdownTimeout(Duration.ofSeconds(1)).build(),
                duration -> {
                });
    }

    private void reply(String type, String json) {
        canned.put(type, (ObjectNode) FakeServer.MAPPER.readTree(json));
    }

    @Test
    public void test_connect_authenticates_and_reads_server_level_answers() throws Exception {
        try (var server = server(); var client = connect(server)) {
            reply("LIST_DATABASES", "{\"type\":\"LIST_DATABASES\",\"status\":\"OK\",\"databases\":[\"shop\"]}");
            reply("SERVER_INFO", "{\"type\":\"SERVER_INFO\",\"status\":\"OK\",\"server\":{\"state\":\"READY\","
                    + "\"version\":\"1.2\"}}");
            reply("GET_DATABASE_STATS", "{\"type\":\"GET_DATABASE_STATS\",\"status\":\"OK\",\"stats\":{\"n\":1}}");
            assertEquals("AUTHENTICATE", server.nextRequest().get("type").asString());
            assertEquals(List.of("shop"), client.listDatabases());
            assertEquals("READY", client.serverInfo().state().orElseThrow());
            assertEquals("1.2", client.serverInfo().version().orElseThrow());
            assertEquals(1, client.databaseStats().getLong("n").getAsLong());
            client.changePassword("old", "new");
            final var setPassword = server.nextRequestOfType("SET_PASSWORD");
            assertEquals("alice", setPassword.get("username").asString());
            assertEquals("old", setPassword.get("currentPassword").asString());
            assertEquals(server.port(), client.settings().hosts().get(0).port());
        }
    }

    @Test
    public void test_a_refused_login_fails_connect() throws Exception {
        reply("AUTHENTICATE", "{\"type\":\"AUTHENTICATE\",\"status\":\"ERROR\",\"errorCode\":\"401-3\"}");
        try (var server = server()) {
            assertThrows(AuthenticationException.class, () -> connect(server));
        }
    }

    @Test
    public void test_change_password_needs_credentials() throws Exception {
        try (var server = server();
                var client = LwnrdbClient.connect(ClientSettings.builder().host("127.0.0.1", server.port()).build())) {
            assertThrows(IllegalStateException.class, () -> client.changePassword("a", "b"));
        }
    }

    @Test
    public void test_a_transaction_commits_on_return_on_one_connection() throws Exception {
        reply("START_TRANSACTION", "{\"type\":\"START_TRANSACTION\",\"status\":\"OK\",\"transactionId\":\"t1\"}");
        try (var server = server(); var client = connect(server)) {
            final var id = client.transaction(transaction -> {
                final var orders = transaction.collection("shop", "orders");
                orders.save(Document.of("_id", "a"));
                orders.delete("b");
                orders.findById("a");
                orders.saveAll(List.of(Document.of("_id", "c")));
                orders.query().filter(field("x").eq(1)).toList();
                assertEquals("shop", orders.databaseName());
                assertEquals("orders", orders.name());
                return transaction.id();
            });
            assertEquals("t1", id);
            final var types = new ArrayList<String>();
            ObjectNode request;
            do {
                request = server.nextRequest();
                types.add(request.get("type").asString());
            } while (!"COMMIT_TRANSACTION".equals(request.get("type").asString()));
            assertEquals(List.of("AUTHENTICATE", "START_TRANSACTION", "SAVE", "DELETE", "FIND_BY_ID", "BULK_SAVE",
                    "AGGREGATE", "COMMIT_TRANSACTION"), types);
            assertEquals(1, server.acceptedConnections());
        }
    }

    @Test
    public void test_a_failing_body_rolls_back_and_rethrows_the_original() throws Exception {
        try (var server = server(); var client = connect(server)) {
            final var failure = new IllegalStateException("body failed");
            final var thrown = assertThrows(IllegalStateException.class, () -> client.transaction(transaction -> {
                throw failure;
            }));
            assertSame(failure, thrown);
            server.nextRequestOfType("ROLLBACK_TRANSACTION");
        }
    }

    @Test
    public void test_a_failed_rollback_is_suppressed_behind_the_original() throws Exception {
        reply("ROLLBACK_TRANSACTION",
                "{\"type\":\"ROLLBACK_TRANSACTION\",\"status\":\"ERROR\",\"errorCode\":\"409-4\"}");
        try (var server = server(); var client = connect(server)) {
            final var thrown = assertThrows(IllegalStateException.class, () -> client.transaction(transaction -> {
                throw new IllegalStateException("body failed");
            }));
            assertInstanceOf(ConflictException.class, thrown.getSuppressed()[0]);
        }
    }

    @Test
    public void test_an_unknown_commit_outcome_skips_the_rollback() throws Exception {
        reply("COMMIT_TRANSACTION", "{\"type\":\"COMMIT_TRANSACTION\",\"status\":\"ERROR\",\"errorCode\":\"500-33\"}");
        try (var server = server(); var client = connect(server)) {
            assertThrows(UnknownOutcomeException.class, () -> client.transaction(transaction -> "x"));
            server.nextRequestOfType("COMMIT_TRANSACTION");
            reply("COMMIT_TRANSACTION", "{\"type\":\"COMMIT_TRANSACTION\",\"status\":\"OK\"}");
            client.listDatabases();
            assertEquals("AUTHENTICATE", server.nextRequest().get("type").asString());
            assertEquals("LIST_DATABASES", server.nextRequest().get("type").asString());
            assertEquals(2, server.acceptedConnections());
        }
    }

    @Test
    public void test_an_unused_transaction_rolls_back_on_close_and_refuses_reuse() throws Exception {
        try (var server = server(); var client = connect(server)) {
            final var transaction = client.beginTransaction();
            assertTrue(transaction.isOpen());
            transaction.close();
            assertFalse(transaction.isOpen());
            server.nextRequestOfType("ROLLBACK_TRANSACTION");
            assertThrows(IllegalStateException.class, transaction::commit);
            assertThrows(IllegalStateException.class, () -> transaction.collection("shop", "orders"));
            transaction.close();
        }
    }

    @Test
    public void test_a_refused_start_returns_the_connection() throws Exception {
        reply("START_TRANSACTION", "{\"type\":\"START_TRANSACTION\",\"status\":\"ERROR\",\"errorCode\":\"409-3\"}");
        try (var server = server(); var client = connect(server)) {
            assertThrows(ConflictException.class, client::beginTransaction);
            client.listDatabases();
            assertEquals(1, server.acceptedConnections());
        }
    }

    @Test
    public void test_not_found_answers_become_empty_results() throws Exception {
        reply("FIND_BY_ID", "{\"type\":\"FIND_BY_ID\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-2\"}");
        reply("AGGREGATE", "{\"type\":\"AGGREGATE\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-3\"}");
        reply("LIST_USERS", "{\"type\":\"LIST_USERS\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-5\"}");
        reply("GET_SCHEMA", "{\"type\":\"GET_SCHEMA\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-4\"}");
        try (var server = server(); var client = connect(server)) {
            final var orders = client.database("shop").collection("orders");
            assertTrue(orders.findById("a").isEmpty());
            assertTrue(orders.query().toList().isEmpty());
            assertEquals(0, orders.query().count());
            assertTrue(orders.query().first().isEmpty());
            assertTrue(client.admin().users().list().isEmpty());
            assertTrue(orders.schema().get().isEmpty());
        }
    }

    @Test
    public void test_other_not_found_codes_are_still_raised() throws Exception {
        reply("FIND_BY_ID", "{\"type\":\"FIND_BY_ID\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-4\"}");
        reply("AGGREGATE", "{\"type\":\"AGGREGATE\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-4\"}");
        reply("LIST_USERS", "{\"type\":\"LIST_USERS\",\"status\":\"NOT_FOUND\",\"errorCode\":\"404-4\"}");
        try (var server = server(); var client = connect(server)) {
            final var orders = client.database("shop").collection("orders");
            assertThrows(io.github.christiancg.lwnrdb.client.error.NotFoundException.class, () -> orders.findById("a"));
            assertThrows(io.github.christiancg.lwnrdb.client.error.NotFoundException.class,
                    () -> orders.query().toList());
            assertThrows(io.github.christiancg.lwnrdb.client.error.NotFoundException.class,
                    () -> client.admin().users().list());
        }
    }

    @Test
    public void test_listen_through_the_client_and_close_releases_everything() throws Exception {
        reply("LISTEN",
                "{\"type\":\"LISTEN\",\"status\":\"OK\",\"listenId\":\"L1\",\"isUpdate\":false," + "\"results\":[]}");
        try (var server = server()) {
            final var client = connect(server);
            final var subscription = client.database("shop").collection("orders").listen(update -> {
            });
            assertEquals("L1", subscription.listenId());
            client.close();
            assertFalse(subscription.isActive());
        }
    }
}
