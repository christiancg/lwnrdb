package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenDeliveryTest {
    private static final String LISTENER = "listen-delivery-admin";
    private static final String STALE_HASH = "hash-before-a-write-in-the-window";

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private ListenManager manager;
    private UUID clientId;
    private ByteArrayOutputStream pushed;

    @BeforeAll
    static void setUpAll() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry(LISTENER, "unused", true, new HashSet<>(), new HashMap<>(), new HashMap<>()));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void setUp() {
        manager = new ListenManager();
        clientId = clientTracker.registerForwardedClient(LISTENER);
        pushed = new ByteArrayOutputStream();
        clientTracker.registerOutbound(clientId, TestUtils.inlineOutbound(pushed));
    }

    @AfterEach
    void tearDown() {
        clientTracker.removeById(clientId);
    }

    private static AggregateRequest dirtyRequest() {
        return dirtyRequest(TestGlobals.COLL);
    }

    private static AggregateRequest dirtyRequest(String collection) {
        final var request = new AggregateRequest(TestGlobals.DB, collection);
        request.setAggregationSteps(List.of());
        request.setDirtyRead(true);
        return request;
    }

    @SuppressWarnings("unchecked")
    private LinkedBlockingQueue<UUID> managerQueue() throws Exception {
        return (LinkedBlockingQueue<UUID>) TestUtils.getPrivateField(manager, "dirtyQueue", LinkedBlockingQueue.class);
    }

    private void drain(LinkedBlockingQueue<UUID> queue) throws InterruptedException {
        final var worker = new Thread(new ListenProcessorThread(queue, manager));
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(300);
        worker.interrupt();
        worker.join(1000);
    }

    private void drainOnce(UUID listenId) throws InterruptedException {
        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(listenId);
        drain(queue);
    }

    @Test
    public void test_an_undelivered_listen_is_not_pushed_and_keeps_its_initial_hash() throws Exception {
        final var listenId = manager.register(clientId, dirtyRequest(), STALE_HASH);

        drainOnce(listenId);

        assertEquals("", pushed.toString(StandardCharsets.UTF_8),
                "nothing may reach the client before its LISTEN response");
        assertEquals(STALE_HASH, manager.getRegistration(listenId).lastHash().get());
        assertNotNull(manager.getRegistration(listenId));
    }

    @Test
    public void test_mark_delivered_queues_the_listen_once() throws Exception {
        final var listenId = manager.register(clientId, dirtyRequest(), STALE_HASH);

        manager.markDelivered(listenId);
        manager.markDelivered(listenId);

        assertEquals(List.of(listenId), List.copyOf(managerQueue()));
        assertTrue(manager.getRegistration(listenId).delivered().get());
    }

    @Test
    public void test_a_write_inside_the_window_is_pushed_after_delivery() throws Exception {
        final var listenId = manager.register(clientId, dirtyRequest(), STALE_HASH);
        drainOnce(listenId);

        manager.markDelivered(listenId);
        drain(managerQueue());

        assertEquals(ResultHasher.hash(List.of(), false), manager.getRegistration(listenId).lastHash().get());
        final var frames = pushed.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(1, frames.size(), "the write landing before delivery is pushed exactly once");
        assertTrue(frames.getFirst().contains(listenId.toString()));
    }

    @Test
    public void test_mark_delivered_of_an_unregistered_listen_is_a_no_op() throws Exception {
        manager.markDelivered(UUID.randomUUID());

        assertTrue(managerQueue().isEmpty());
    }

    @Test
    public void listenPushUsesBytes() throws Exception {
        final var document = new JsonObject();
        document.addProperty("_id", "cjk");
        document.addProperty("text", "界面 ünïcødé 😀");
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.JOIN_COLL);
        save.set_id("cjk");
        save.setObject(document);
        SaveOperationHelper.executeSave(save);
        final var request = dirtyRequest(TestGlobals.JOIN_COLL);
        final var listenId = manager.register(clientId, request, STALE_HASH);

        manager.markDelivered(listenId);
        drain(managerQueue());

        final var results = AggregationOperationHelper.processAggregation(request);
        final var frame = IocContainer.get(EJson.class)
                .toJson(new ListenResponse(listenId.toString(), results, ResultHasher.hash(results, false), true));
        assertTrue(frame.contains("界面"), frame);
        assertArrayEquals((frame + System.lineSeparator()).getBytes(StandardCharsets.UTF_8), pushed.toByteArray());
    }
}
