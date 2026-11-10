package io.github.christiancg.lwnrdb.client.it;

import static io.github.christiancg.lwnrdb.client.query.Filters.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Collection;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.ListenHandler;
import io.github.christiancg.lwnrdb.client.ListenUpdate;
import io.github.christiancg.lwnrdb.client.error.ListenEndedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ListenIT extends ServerIT {
    private Collection<Document> orders;

    private static final class Events implements ListenHandler<Document> {
        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

        @Override
        public void onUpdate(ListenUpdate<Document> update) {
            queue.add(update);
        }

        @Override
        public void onEnded(ListenEndedException reason) {
            queue.add(reason);
        }

        @Override
        public void onError(Throwable error) {
            queue.add(error);
        }

        Object next(long seconds) throws InterruptedException {
            return queue.poll(seconds, TimeUnit.SECONDS);
        }

        @SuppressWarnings("unchecked")
        ListenUpdate<Document> update() throws InterruptedException {
            final var event = next(10);
            assertInstanceOf(ListenUpdate.class, event);
            return (ListenUpdate<Document>) event;
        }
    }

    @Override
    protected Map<String, String> config() {
        return Map.of("maxListensPerConnection", "2");
    }

    @BeforeAll
    public void createCollection() {
        client.database("listen_it").create();
        orders = client.database("listen_it").collection("orders");
        orders.create();
    }

    @Test
    public void test_a_matching_write_pushes_and_a_closed_subscription_stops() throws Exception {
        final var events = new Events();
        final var subscription = orders.listen(orders.query().filter(field("status").eq("pending")), events);
        assertTrue(events.update().initial());
        orders.save(Document.of("_id", "p1", "status", "pending"));
        final var pushed = events.update();
        assertFalse(pushed.initial());
        assertEquals("p1", pushed.results().get(0).id().orElseThrow());
        orders.save(Document.of("_id", "x1", "status", "done"));
        assertNull(events.next(1));
        subscription.close();
        orders.save(Document.of("_id", "p2", "status", "pending"));
        assertNull(events.next(1));
    }

    @Test
    public void test_dropping_the_collection_ends_the_listen() throws Exception {
        client.database("listen_it").collection("doomed").create();
        final var doomed = client.database("listen_it").collection("doomed");
        final var events = new Events();
        final var subscription = doomed.listen(events);
        events.update();
        doomed.drop();
        final var ended = assertInstanceOf(ListenEndedException.class, events.next(10));
        assertEquals("410-1", ended.rawCode().orElseThrow());
        subscription.close();
    }

    @Test
    public void test_listens_past_the_per_connection_limit_spread_over_connections() throws Exception {
        final var subscriptions = new ArrayList<io.github.christiancg.lwnrdb.client.Subscription>();
        final var handlers = new ArrayList<Events>();
        for (var i = 0; i < 5; i++) {
            final var events = new Events();
            handlers.add(events);
            subscriptions.add(orders.listen(orders.query().filter(field("status").eq("fanout")), events));
            events.update();
        }
        orders.save(Document.of("_id", "f1", "status", "fanout"));
        for (final var events : handlers) {
            assertEquals(List.of("f1"), events.update().results().stream().map(row -> row.id().orElseThrow()).toList());
        }
        subscriptions.forEach(io.github.christiancg.lwnrdb.client.Subscription::close);
    }
}
