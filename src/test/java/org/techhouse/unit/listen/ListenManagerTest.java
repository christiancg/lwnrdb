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

public class ListenManagerTest {

    private ListenManager manager;

    @BeforeEach
    void setUp() {
        manager = new ListenManager();
    }

    private AggregateRequest dirtyRequest(String db, String coll) {
        final var req = new AggregateRequest(db, coll);
        req.setAggregationSteps(List.of());
        req.setDirtyRead(true);
        return req;
    }

    @SuppressWarnings("unchecked")
    private LinkedBlockingQueue<UUID> queue() throws NoSuchFieldException, IllegalAccessException {
        return (LinkedBlockingQueue<UUID>) TestUtils.getPrivateField(manager, "dirtyQueue", LinkedBlockingQueue.class);
    }

    private void registerDbColl() {
        manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");
    }

    @Test
    public void register_returnsNonNullListenId() {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");

        assertNotNull(listenId);
    }

    @Test
    public void register_registrationIsRetrievable() {
        final var clientId = UUID.randomUUID();
        final var listenId = manager.register(clientId, dirtyRequest("db", "coll"), "hash");

        final var reg = manager.getRegistration(listenId);

        assertNotNull(reg);
        assertEquals(listenId, reg.listenId());
        assertEquals(clientId, reg.clientId());
        assertEquals("hash", reg.lastHash().get());
    }

    @Test
    public void unregister_existingListen_returnsTrue() {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");

        assertTrue(manager.unregister(listenId));
    }

    @Test
    public void unregister_nonExistentListen_returnsFalse() {
        assertFalse(manager.unregister(UUID.randomUUID()));
    }

    @Test
    public void unregister_registrationIsGone() {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");
        manager.unregister(listenId);

        assertNull(manager.getRegistration(listenId));
    }

    @Test
    public void unregisterAllForClient_removesOnlyThatClientsRegistrations() {
        final var clientA = UUID.randomUUID();
        final var clientB = UUID.randomUUID();
        final var idA1 = manager.register(clientA, dirtyRequest("db", "coll"), "hash");
        final var idA2 = manager.register(clientA, dirtyRequest("db", "other"), "hash");
        final var idB = manager.register(clientB, dirtyRequest("db", "coll"), "hash");

        manager.unregisterAllForClient(clientA);

        assertNull(manager.getRegistration(idA1));
        assertNull(manager.getRegistration(idA2));
        assertNotNull(manager.getRegistration(idB));
    }

    @Test
    public void endAllForCollection_removesOnlyThatCollectionsRegistrations() {
        final var clientA = UUID.randomUUID();
        final var clientB = UUID.randomUUID();
        final var idA = manager.register(clientA, dirtyRequest("db", "coll"), "hash");
        final var idB = manager.register(clientB, dirtyRequest("db", "other"), "hash");

        manager.endAllForCollection("db", "coll", ListenManager.COLLECTION_DROPPED);

        assertNull(manager.getRegistration(idA));
        assertNotNull(manager.getRegistration(idB));
    }

    @Test
    public void endAllForCollection_emptyCollection_doesNotThrow() {
        assertDoesNotThrow(() -> manager.endAllForCollection("db", "nonexistent", ListenManager.COLLECTION_DROPPED));
    }

    @Test
    public void endAllForDatabase_removesAllCollectionsInDatabase() {
        final var client = UUID.randomUUID();
        final var idA = manager.register(client, dirtyRequest("mydb", "coll1"), "hash");
        final var idB = manager.register(client, dirtyRequest("mydb", "coll2"), "hash");
        final var idC = manager.register(client, dirtyRequest("other", "coll1"), "hash");

        manager.endAllForDatabase("mydb", ListenManager.DATABASE_DROPPED);

        assertNull(manager.getRegistration(idA));
        assertNull(manager.getRegistration(idB));
        assertNotNull(manager.getRegistration(idC));
    }

    @Test
    public void endAllForDatabase_emptyDatabase_doesNotThrow() {
        assertDoesNotThrow(() -> manager.endAllForDatabase("nonexistent", ListenManager.DATABASE_DROPPED));
    }

    @Test
    public void end_unknownListen_returnsFalse() {
        assertFalse(manager.end(UUID.randomUUID(), ListenManager.COLLECTION_DROPPED));
    }

    @Test
    public void end_twice_returnsFalseTheSecondTime() {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");

        assertTrue(manager.end(listenId, ListenManager.COLLECTION_DROPPED));
        assertFalse(manager.end(listenId, ListenManager.DATABASE_DROPPED));
        assertNull(manager.getRegistration(listenId));
    }

    @Test
    public void markDirty_noListens_doesNotThrow() {
        assertDoesNotThrow(() -> manager.markDirty("db", "coll"));
    }

    @Test
    public void startAndStopWorkers_doesNotThrow() {
        assertDoesNotThrow(() -> {
            manager.startWorkers();
            manager.stopWorkers();
        });
    }

    @Test
    public void getRegistration_nullAfterStopWorkers() {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");
        manager.stopWorkers();

        assertNull(manager.getRegistration(listenId));
    }

    @Test
    public void test_a_single_defer_flushes_once() throws Exception {
        registerDbColl();
        manager.deferNotifications();
        manager.markDirty("db", "coll");
        manager.markDirty("db", "coll");
        assertEquals(0, queue().size());

        manager.flushDeferredNotifications();

        assertEquals(1, queue().size());
    }

    @Test
    public void test_a_nested_defer_does_not_flush_the_outer_scope() throws Exception {
        registerDbColl();
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
        registerDbColl();
        manager.register(UUID.randomUUID(), dirtyRequest("other", "coll"), "hash");
        manager.deferNotifications();
        manager.markDirty("db", "coll");
        manager.deferNotifications();
        manager.markDirty("other", "coll");
        manager.flushDeferredNotifications();
        manager.flushDeferredNotifications();

        assertEquals(2, queue().size(), "the inner scope must not discard what the outer one had already marked");
    }

    @Test
    public void test_a_flush_with_no_matching_defer_is_a_no_op() {
        registerDbColl();
        assertDoesNotThrow(manager::flushDeferredNotifications);
    }

    @Test
    public void test_a_mark_outside_any_defer_enqueues_immediately() throws Exception {
        registerDbColl();
        manager.markDirty("db", "coll");
        assertEquals(1, queue().size());
    }

    @Test
    public void test_a_burst_of_writes_enqueues_one_run() throws Exception {
        registerDbColl();

        for (var i = 0; i < 100; i++) {
            manager.markDirty("db", "coll");
        }

        assertEquals(1, queue().size(), "a write burst must collapse to a single queued re-run");
    }

    @Test
    public void test_each_registration_is_queued_once() throws Exception {
        registerDbColl();
        registerDbColl();

        for (var i = 0; i < 50; i++) {
            manager.markDirty("db", "coll");
        }

        assertEquals(2, queue().size());
    }

    @Test
    public void test_a_write_after_the_marker_is_cleared_queues_again() throws Exception {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");
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
        registerDbColl();

        manager.markDirty("db", "coll");
        manager.markDirty("db", "coll");

        assertEquals(1, queue().size());
    }

    @Test
    public void test_unrelated_collection_is_not_queued() throws Exception {
        registerDbColl();

        manager.markDirty("db", "other");

        assertEquals(0, queue().size());
    }

    @Test
    public void test_a_transactional_commit_notifies_once_with_the_final_state() throws Exception {
        registerDbColl();

        manager.deferNotifications();
        for (var i = 0; i < 5; i++) {
            manager.markDirty("db", "coll");
        }
        assertEquals(0, queue().size(),
                "a re-run queued mid-commit would hash a result holding some of the transaction's ops and not others");

        manager.flushDeferredNotifications();

        assertEquals(1, queue().size(), "the commit notifies once, after every op has been applied");
    }

    @Test
    public void test_deferral_does_not_hold_back_another_thread() throws Exception {
        registerDbColl();
        manager.deferNotifications();
        try {
            final var writer = new Thread(() -> manager.markDirty("db", "coll"));
            writer.start();
            writer.join(5_000L);

            assertEquals(1, queue().size(), "a standalone write on another thread must notify immediately");
        } finally {
            manager.flushDeferredNotifications();
        }
    }
}
