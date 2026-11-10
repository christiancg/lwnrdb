package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.DocumentCodec;
import io.github.christiancg.lwnrdb.client.ListenHandler;
import io.github.christiancg.lwnrdb.client.ListenUpdate;
import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.ListenEndedException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.testsupport.FakeServer;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class ListensTest {
    private static final String PUSH = "{\"type\":\"LISTEN\",\"status\":\"OK\",\"listenId\":\"LISTEN_ID\",\"isUpdate\":true,"
            + "\"results\":[{\"_id\":\"ROW_ID\"}],\"resultHash\":\"h2\"}\n";

    private static String push(String listenId, String rowId) {
        return PUSH.replace("LISTEN_ID", listenId).replace("ROW_ID", rowId);
    }

    private static final class Recorded implements ListenHandler<Document> {
        final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        volatile boolean throwOnUpdate;

        @Override
        public void onUpdate(ListenUpdate<Document> update) {
            events.add(update);
            if (throwOnUpdate) {
                throw new IllegalStateException("handler failed");
            }
        }

        @Override
        public void onEnded(ListenEndedException reason) {
            events.add(reason);
        }

        @Override
        public void onError(Throwable error) {
            events.add(error);
        }

        Object next() throws InterruptedException {
            final var event = events.poll(5, TimeUnit.SECONDS);
            if (event == null) {
                throw new AssertionError("no listen event within 5 s");
            }
            return event;
        }
    }

    private static FakeServer listenServer(int perConnection, boolean pushBeforeReply) throws IOException {
        final var ids = new AtomicInteger();
        final Map<FakeServer.Session, AtomicInteger> perSession = new ConcurrentHashMap<>();
        return new FakeServer((request, session) -> {
            final var type = request.get("type").asString();
            if ("LISTEN".equals(type)) {
                final var registered = perSession.computeIfAbsent(session, ignored -> new AtomicInteger());
                if (registered.get() >= perConnection) {
                    session.send(FakeServer.error(request, "ERROR", "400-25"));
                    return;
                }
                registered.incrementAndGet();
                final var id = "L" + ids.incrementAndGet();
                if (pushBeforeReply) {
                    session.sendRaw(push(id, "early"));
                }
                session.sendRaw("{\"type\":\"LISTEN\",\"status\":\"OK\",\"listenId\":\"" + id
                        + "\",\"isUpdate\":false,\"results\":[{\"_id\":\"initial\"}],\"resultHash\":\"h1\"}\n");
            } else {
                session.send(FakeServer.ok(request));
            }
        });
    }

    private static Listens listens(FakeServer server) {
        final var settings = Wire.settings(server).build();
        return new Listens(Wire.factory(settings), new RequestEncoder(Wire.JSON), settings.requestTimeout());
    }

    @SuppressWarnings("unchecked")
    private static ListenUpdate<Document> update(Object event) {
        return (ListenUpdate<Document>) event;
    }

    @Test
    public void test_initial_results_then_pushes_arrive_in_order() throws Exception {
        try (var server = listenServer(10, false); var listens = listens(server)) {
            final var handler = new Recorded();
            final var subscription = listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), handler);
            final var initial = update(handler.next());
            assertTrue(initial.initial());
            assertEquals("initial", initial.results().get(0).id().orElseThrow());
            server.awaitSession(0).sendRaw(push(subscription.listenId(), "next"));
            final var pushed = update(handler.next());
            assertFalse(pushed.initial());
            assertEquals("h2", pushed.resultHash());
            assertTrue(subscription.isActive());
            subscription.close();
            subscription.close();
            assertEquals(subscription.listenId(), server.nextRequestOfType("STOP_LISTEN").get("listenId").asString());
            assertFalse(subscription.isActive());
        }
    }

    @Test
    public void test_a_push_racing_the_reply_is_delivered_after_the_initial_results() throws Exception {
        try (var server = listenServer(10, true); var listens = listens(server)) {
            final var handler = new Recorded();
            listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), handler);
            assertTrue(update(handler.next()).initial());
            assertEquals("early", update(handler.next()).results().get(0).id().orElseThrow());
        }
    }

    @Test
    public void test_an_end_frame_calls_on_ended_and_a_throwing_handler_reaches_on_error() throws Exception {
        try (var server = listenServer(10, false); var listens = listens(server)) {
            final var handler = new Recorded();
            final var subscription = listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), handler);
            handler.next();
            handler.throwOnUpdate = true;
            final var session = server.awaitSession(0);
            session.sendRaw(push(subscription.listenId(), "boom"));
            handler.next();
            assertInstanceOf(IllegalStateException.class, handler.next());
            session.sendRaw("{\"type\":\"LISTEN\",\"status\":\"NOT_FOUND\",\"errorCode\":\"410-1\",\"message\":"
                    + "\"dropped\",\"listenId\":\"" + subscription.listenId() + "\"}\n");
            final var ended = assertInstanceOf(ListenEndedException.class, handler.next());
            assertEquals("410-1", ended.rawCode().orElseThrow());
        }
    }

    @Test
    @SuppressWarnings("BusyWait")
    public void test_a_channel_whose_last_listen_ended_is_never_reused() throws Exception {
        try (var server = listenServer(10, false); var listens = listens(server)) {
            final var handler = new Recorded();
            final var first = listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), handler);
            handler.next();
            server.awaitSession(0).sendRaw("{\"type\":\"LISTEN\",\"status\":\"NOT_FOUND\",\"errorCode\":\"410-1\","
                    + "\"message\":\"dropped\",\"listenId\":\"" + first.listenId() + "\"}\n");
            assertInstanceOf(ListenEndedException.class, handler.next());
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (listens.openChannels() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(0, listens.openChannels());
            final var second = new Recorded();
            listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), second);
            assertTrue(update(second.next()).initial());
            assertEquals(2, server.acceptedConnections());
        }
    }

    @Test
    public void test_a_full_channel_opens_another_connection() throws Exception {
        try (var server = listenServer(1, false); var listens = listens(server)) {
            listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), new Recorded());
            listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), new Recorded());
            assertEquals(2, listens.openChannels());
            assertEquals(2, server.acceptedConnections());
        }
    }

    @Test
    public void test_a_refusal_on_a_fresh_channel_is_reported() throws Exception {
        try (var server = listenServer(0, false); var listens = listens(server)) {
            final var error = assertThrows(LwnrdbException.class,
                    () -> listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), new Recorded()));
            assertEquals("400-25", error.rawCode().orElseThrow());
            assertEquals(0, listens.openChannels());
        }
    }

    @Test
    public void test_another_refusal_is_not_retried_on_a_new_channel() throws Exception {
        final var first = new AtomicInteger();
        try (var server = new FakeServer((request, session) -> {
            if (first.getAndIncrement() == 0) {
                session.sendRaw("{\"type\":\"LISTEN\",\"status\":\"OK\",\"listenId\":\"L1\",\"isUpdate\":false,"
                        + "\"results\":[]}\n");
            } else {
                session.send(FakeServer.error(request, "FORBIDDEN", "403-1"));
            }
        }); var listens = listens(server)) {
            listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), new Recorded());
            assertThrows(LwnrdbException.class,
                    () -> listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), new Recorded()));
            assertEquals(1, server.acceptedConnections());
        }
    }

    @Test
    public void test_a_lost_channel_reports_an_error_to_every_subscription() throws Exception {
        try (var server = listenServer(10, false); var listens = listens(server)) {
            final var handler = new Recorded();
            final var subscription = listens.listen("shop", "orders", List.of(), DocumentCodec.identity(), handler);
            handler.next();
            server.awaitSession(0).close();
            assertInstanceOf(ConnectionLostException.class, handler.next());
            assertFalse(subscription.isActive());
            subscription.close();
        }
    }
}
