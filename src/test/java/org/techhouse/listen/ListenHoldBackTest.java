package org.techhouse.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenHoldBackTest {
    private static final String LISTENER = "listen-holdback-admin";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ListenManager manager = new ListenManager();
    private final List<String> applyingKeys = List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));
    private UUID clientId;
    private UUID listenId;
    private ListenRegistration registration;
    private String initialHash;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry(LISTENER, "unused", true, new HashSet<>(), new HashMap<>(), new HashMap<>()));
        clientId = clientTracker.registerForwardedClient(LISTENER);
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        request.setDirtyRead(true);
        initialHash = ResultHasher.hash(List.of(), false);
        listenId = manager.register(clientId, request, initialHash);
        registration = manager.getRegistration(listenId);
        manager.markDelivered(listenId);
        dirtyQueue().clear();
        manager.dequeued(listenId);
    }

    @AfterEach
    public void tearDown() throws Exception {
        clientTracker.removeById(clientId);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @SuppressWarnings("unchecked")
    private LinkedBlockingQueue<UUID> dirtyQueue() throws Exception {
        return (LinkedBlockingQueue<UUID>) TestUtils.getPrivateField(manager, "dirtyQueue", LinkedBlockingQueue.class);
    }

    private void saveDocument(String id) {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString(id));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document);
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void runWorkerOnce() throws InterruptedException {
        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(listenId);
        final var worker = new Thread(new ListenProcessorThread(queue, manager));
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(300);
        worker.interrupt();
        worker.join(1000);
    }

    @Test
    public void test_a_rerun_overlapping_a_deferred_apply_is_held_back_until_the_flush() throws Exception {
        manager.deferNotifications(applyingKeys);
        saveDocument("half-applied");

        runWorkerOnce();

        assertEquals(initialHash, registration.lastHash().get(), "no frame is computed from a half-applied state");
        assertFalse(dirtyQueue().contains(listenId));

        manager.flushDeferredNotifications();

        assertTrue(dirtyQueue().contains(listenId), "the flush re-queues the listen it held back");
        runWorkerOnce();
        assertNotEquals(initialHash, registration.lastHash().get());
    }

    @Test
    public void test_nested_deferrals_release_their_keys_only_at_the_outermost_flush() throws Exception {
        manager.deferNotifications(applyingKeys);
        manager.deferNotifications(applyingKeys);
        saveDocument("nested");
        manager.flushDeferredNotifications();

        runWorkerOnce();

        assertEquals(initialHash, registration.lastHash().get());

        manager.flushDeferredNotifications();
        runWorkerOnce();

        assertNotEquals(initialHash, registration.lastHash().get());
    }

    @Test
    public void test_a_hold_back_after_the_apply_ended_requeues_itself() throws Exception {
        manager.holdBack(listenId, registration.collectionKeys());

        assertTrue(dirtyQueue().contains(listenId), "nothing is applying, so nothing else would re-queue it");
    }

    @Test
    public void test_a_deferral_without_keys_holds_nothing_back() throws Exception {
        manager.deferNotifications();
        saveDocument("unkeyed");
        manager.flushDeferredNotifications();

        runWorkerOnce();

        assertNotEquals(initialHash, registration.lastHash().get());
    }
}
