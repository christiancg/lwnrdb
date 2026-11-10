package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.error.RequestTimeoutException;
import io.github.christiancg.lwnrdb.client.error.ServerClosedConnectionException;
import io.github.christiancg.lwnrdb.testsupport.FakeServer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

public class WireConnectionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    public void test_pipelined_replies_complete_in_order_and_a_push_does_not_shift_them() throws Exception {
        try (var server = new FakeServer((request, session) -> {
            if (request.get("n").asInt() == 1) {
                session.sendRaw("{\"type\":\"LISTEN\",\"status\":\"OK\",\"listenId\":\"x\",\"isUpdate\":true}\n");
            }
            session.send(FakeServer.ok(request).put("n", request.get("n").asInt()));
        }); var connection = Wire.connect(server)) {
            final var pushes = new LinkedBlockingQueue<ObjectNode>();
            connection.onPush(pushes::add);
            final var first = connection.send(Wire.request("AGGREGATE").put("n", 1));
            final var second = connection.send(Wire.request("AGGREGATE").put("n", 2));
            assertEquals(1, first.get(5, TimeUnit.SECONDS).get("n").asInt());
            assertEquals(2, second.get(5, TimeUnit.SECONDS).get("n").asInt());
            final var push = pushes.poll(5, TimeUnit.SECONDS);
            assertNotNull(push);
            assertEquals("x", push.get("listenId").asString());
        }
    }

    @Test
    public void test_an_unsolicited_close_frame_fails_every_pending_request() throws Exception {
        try (var server = new FakeServer((request, session) -> session.sendRaw(
                "{\"type\":\"CLOSE_CONNECTION\",\"status\":\"ERROR\",\"message\":\"too big\",\"errorCode\":\"400-22\"}\n"));
                var connection = Wire.connect(server)) {
            final var closed = new CompletableFuture<LwnrdbException>();
            connection.onClose(closed::complete);
            final var reply = connection.send(Wire.request("SAVE"));
            final var error = assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS));
            final var cause = assertInstanceOf(ServerClosedConnectionException.class, error.getCause());
            assertEquals("400-22", cause.rawCode().orElseThrow());
            assertInstanceOf(ServerClosedConnectionException.class, closed.get(5, TimeUnit.SECONDS));
            assertFalse(connection.isOpen());
            assertThrows(ServerClosedConnectionException.class,
                    () -> connection.request(Wire.request("SAVE"), TIMEOUT));
        }
    }

    @Test
    public void test_a_requested_close_completes_normally() throws Exception {
        try (var server = new FakeServer(FakeServer.okToEverything()); var connection = Wire.connect(server)) {
            connection.closeGracefully(TIMEOUT);
            assertFalse(connection.isOpen());
            connection.closeGracefully(TIMEOUT);
        }
    }

    @Test
    public void test_a_timeout_closes_the_connection() throws Exception {
        try (var server = new FakeServer((request, session) -> {
        }); var connection = Wire.connect(server)) {
            assertThrows(RequestTimeoutException.class,
                    () -> connection.request(Wire.request("SAVE"), Duration.ofMillis(100)));
            assertFalse(connection.isOpen());
        }
    }

    @Test
    public void test_an_oversized_reply_fails_only_its_request() throws Exception {
        try (var server = new FakeServer((request, session) -> {
            if ("big".equals(request.path("size").asString())) {
                session.sendRaw("{\"type\":\"AGGREGATE\",\"pad\":\"" + "x".repeat(10_000) + "\"}\n");
            } else {
                session.send(FakeServer.ok(request));
            }
        }); var connection = Wire.connect(server)) {
            final var error = assertThrows(LwnrdbException.class,
                    () -> connection.request(Wire.request("AGGREGATE").put("size", "big"), TIMEOUT));
            assertTrue(error.getMessage().contains("maxReplyBytes"));
            assertEquals("OK", connection.request(Wire.request("AGGREGATE"), TIMEOUT).get("status").asString());
        }
    }

    @Test
    public void test_a_reset_fails_pending_requests_and_later_sends() throws Exception {
        try (var server = new FakeServer((request, session) -> session.close());
                var connection = Wire.connect(server)) {
            assertThrows(ConnectionLostException.class, () -> connection.request(Wire.request("SAVE"), TIMEOUT));
            assertThrows(ConnectionLostException.class, () -> connection.request(Wire.request("SAVE"), TIMEOUT));
        }
    }

    @Test
    public void test_an_unrequested_reply_or_malformed_frame_closes_the_connection() throws Exception {
        try (var server = new FakeServer(FakeServer.okToEverything()); var connection = Wire.connect(server)) {
            final var closed = new CompletableFuture<LwnrdbException>();
            connection.onClose(closed::complete);
            server.awaitSession(0).sendRaw("{\"type\":\"SAVE\",\"status\":\"OK\"}\n");
            assertInstanceOf(ConnectionLostException.class, closed.get(5, TimeUnit.SECONDS));
        }
        try (var server = new FakeServer((request, session) -> session.sendRaw("[1]\n"));
                var connection = Wire.connect(server)) {
            assertThrows(LwnrdbException.class, () -> connection.request(Wire.request("SAVE"), TIMEOUT));
        }
    }

    @Test
    public void test_a_line_that_is_not_json_fails_only_its_request() throws Exception {
        try (var server = new FakeServer((request, session) -> {
            if ("bad".equals(request.path("kind").asString())) {
                session.sendRaw("The command is not valid\n");
            } else {
                session.send(FakeServer.ok(request));
            }
        }); var connection = Wire.connect(server)) {
            final var error = assertThrows(LwnrdbException.class,
                    () -> connection.request(Wire.request("SAVE").put("kind", "bad"), TIMEOUT));
            assertTrue(error.getMessage().contains("The command is not valid"), error.getMessage());
            assertTrue(error.rawCode().isEmpty());
            assertTrue(connection.isOpen());
            assertEquals("OK", connection.request(Wire.request("SAVE"), TIMEOUT).get("status").asString());
        }
    }

    @Test
    public void test_an_interrupted_wait_closes_and_keeps_the_flag() throws Exception {
        try (var server = new FakeServer((request, session) -> {
        }); var connection = Wire.connect(server)) {
            Thread.currentThread().interrupt();
            assertThrows(ConnectionLostException.class, () -> connection.request(Wire.request("SAVE"), TIMEOUT));
            assertTrue(Thread.interrupted());
            assertFalse(connection.isOpen());
        }
    }
}
