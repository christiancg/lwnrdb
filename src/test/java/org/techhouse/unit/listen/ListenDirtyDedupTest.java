package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.test.TestUtils;

public class ListenDirtyDedupTest {
    private ListenManager manager;

    @BeforeEach
    void setUp() {
        manager = new ListenManager();
    }

    private AggregateRequest dirtyRequest() {
        final var req = new AggregateRequest("db", "coll");
        req.setAggregationSteps(List.of());
        req.setDirtyRead(true);
        return req;
    }

    @SuppressWarnings("unchecked")
    private LinkedBlockingQueue<UUID> queue() throws NoSuchFieldException, IllegalAccessException {
        return (LinkedBlockingQueue<UUID>) TestUtils.getPrivateField(manager, "dirtyQueue", LinkedBlockingQueue.class);
    }

    @Test
    public void test_a_burst_of_writes_enqueues_one_run() throws Exception {
        manager.register(UUID.randomUUID(), dirtyRequest(), "hash");

        for (var i = 0; i < 100; i++) {
            manager.markDirty("db", "coll");
        }

        assertEquals(1, queue().size(), "a write burst must collapse to a single queued re-run");
    }

    @Test
    public void test_each_registration_is_queued_once() throws Exception {
        manager.register(UUID.randomUUID(), dirtyRequest(), "hash");
        manager.register(UUID.randomUUID(), dirtyRequest(), "hash");

        for (var i = 0; i < 50; i++) {
            manager.markDirty("db", "coll");
        }

        assertEquals(2, queue().size());
    }

    @Test
    public void test_a_write_after_the_marker_is_cleared_queues_again() throws Exception {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest(), "hash");
        manager.markDirty("db", "coll");
        assertEquals(1, queue().size());

        final var taken = queue().take();
        assertEquals(listenId, taken);
        final var dequeued = ListenManager.class.getDeclaredMethod("dequeued", UUID.class);
        dequeued.setAccessible(true);
        dequeued.invoke(manager, taken);

        manager.markDirty("db", "coll");

        assertEquals(1, queue().size(), "a write landing after the marker is cleared must queue another run");
    }

    @Test
    public void test_a_write_while_still_queued_does_not_double_enqueue() throws Exception {
        manager.register(UUID.randomUUID(), dirtyRequest(), "hash");

        manager.markDirty("db", "coll");
        manager.markDirty("db", "coll");

        assertEquals(1, queue().size());
    }

    @Test
    public void test_unrelated_collection_is_not_queued() throws Exception {
        manager.register(UUID.randomUUID(), dirtyRequest(), "hash");

        manager.markDirty("db", "other");

        assertEquals(0, queue().size());
    }
}
