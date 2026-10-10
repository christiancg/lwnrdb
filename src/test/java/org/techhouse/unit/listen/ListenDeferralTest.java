package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.test.TestUtils;

public class ListenDeferralTest {
    private ListenManager manager;

    @BeforeEach
    void setUp() {
        manager = new ListenManager();
        manager.register(UUID.randomUUID(), dirtyRequest(), "hash");
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
    public void test_a_single_defer_flushes_once() throws Exception {
        manager.deferNotifications();
        manager.markDirty("db", "coll");
        manager.markDirty("db", "coll");
        assertEquals(0, queue().size());

        manager.flushDeferredNotifications();

        assertEquals(1, queue().size());
    }

    @Test
    public void test_a_nested_defer_does_not_flush_the_outer_scope() throws Exception {
        manager.deferNotifications();
        manager.markDirty("db", "coll");
        manager.deferNotifications();
        manager.markDirty("db", "coll");

        manager.flushDeferredNotifications();

        assertEquals(0, queue().size(), "the inner scope must not end deferral the outer scope owns");

        manager.flushDeferredNotifications();

        assertEquals(1, queue().size());
    }

    @Test
    public void test_a_nested_defer_preserves_the_outer_scope_pending_keys() throws Exception {
        manager.register(UUID.randomUUID(), otherRequest(), "hash");
        manager.deferNotifications();
        manager.markDirty("db", "coll");
        manager.deferNotifications();
        manager.markDirty("other", "coll");
        manager.flushDeferredNotifications();
        manager.flushDeferredNotifications();

        assertEquals(2, queue().size(), "the inner scope must not discard what the outer one had already marked");
    }

    private AggregateRequest otherRequest() {
        final var req = new AggregateRequest("other", "coll");
        req.setAggregationSteps(List.of());
        req.setDirtyRead(true);
        return req;
    }

    @Test
    public void test_a_flush_with_no_matching_defer_is_a_no_op() {
        assertDoesNotThrow(manager::flushDeferredNotifications);
    }

    @Test
    public void test_a_mark_outside_any_defer_enqueues_immediately() throws Exception {
        manager.markDirty("db", "coll");
        assertEquals(1, queue().size());
    }
}
