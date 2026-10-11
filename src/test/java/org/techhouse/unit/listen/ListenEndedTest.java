package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.StringWriter;
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
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.resp.ListenEndedResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenEndedTest {
    private static final String LISTENER = "listen-ended-admin";
    private static final String READER = "listen-ended-reader";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private ListenManager manager;
    private UUID clientId;
    private StringWriter written;

    @BeforeAll
    static void setUpAll() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry(LISTENER, "unused", true, new HashSet<>(), new HashMap<>(), new HashMap<>()));
        saveReader(PermissionLevel.READ);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void setUp() {
        manager = new ListenManager();
        clientId = connectedClient(LISTENER);
    }

    @AfterEach
    void tearDown() {
        clientTracker.removeById(clientId);
    }

    private static void saveReader(PermissionLevel level) throws Exception {
        final var databasePermissions = new HashMap<String, PermissionLevel>();
        if (level != null) {
            databasePermissions.put(TestGlobals.DB, level);
        }
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry(READER, "unused", false, new HashSet<>(), databasePermissions, new HashMap<>()));
    }

    private UUID connectedClient(String username) {
        final var id = clientTracker.registerForwardedClient(username);
        written = new StringWriter();
        clientTracker.registerWriter(id, new BufferedWriter(written));
        return id;
    }

    private static AggregateRequest request(String db, List<BaseAggregationStep> steps) {
        final var request = new AggregateRequest(db, TestGlobals.COLL);
        request.setAggregationSteps(steps);
        request.setDirtyRead(true);
        return request;
    }

    private UUID listen(AggregateRequest request) {
        return manager.register(clientId, request, ResultHasher.hash(List.of(), false));
    }

    private UUID delivered(AggregateRequest request) {
        final var listenId = listen(request);
        manager.markDelivered(listenId);
        return listenId;
    }

    @SuppressWarnings("BusyWait")
    private void runWorkerOver(UUID... listenIds) throws InterruptedException {
        final var queue = new LinkedBlockingQueue<>(List.of(listenIds));
        final var worker = new Thread(new ListenProcessorThread(queue, manager));
        worker.setDaemon(true);
        worker.start();
        for (var i = 0; i < 100 && !queue.isEmpty(); i++) {
            Thread.sleep(10);
        }
        Thread.sleep(100);
        worker.interrupt();
        worker.join(1000);
        assertFalse(worker.isAlive());
    }

    private List<String> frames() {
        final var text = written.toString();
        return text.isEmpty() ? List.of() : List.of(text.split("\\R"));
    }

    private void assertEndedFrame(String frame, UUID listenId, String reason) {
        final var response = eJson.fromJson(frame, ListenEndedResponse.class);
        assertEquals(ErrorCode.LISTEN_ENDED.getCode(), response.getErrorCode());
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals(listenId.toString(), response.getListenId());
        assertTrue(response.getMessage().endsWith(reason), response.getMessage());
    }

    @Test
    public void dropping_the_collection_pushes_one_terminal_frame() throws Exception {
        final var listenId = delivered(request(TestGlobals.DB, List.of()));

        manager.endAllForCollection(TestGlobals.DB, TestGlobals.COLL, ListenManager.COLLECTION_DROPPED);
        runWorkerOver(listenId, listenId);

        assertEquals(1, frames().size(), written.toString());
        assertEndedFrame(frames().getFirst(), listenId, ListenManager.COLLECTION_DROPPED);
        assertNull(manager.getRegistration(listenId));
    }

    @Test
    public void dropping_a_join_target_ends_the_listen_on_the_primary_collection() throws Exception {
        final var listenId = delivered(request(TestGlobals.DB,
                List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "_id", "joined"))));

        manager.endAllForCollection(TestGlobals.DB, TestGlobals.JOIN_COLL, ListenManager.COLLECTION_DROPPED);
        runWorkerOver(listenId);

        assertEquals(1, frames().size(), written.toString());
        assertEndedFrame(frames().getFirst(), listenId, ListenManager.COLLECTION_DROPPED);
    }

    @Test
    public void dropping_the_database_ends_every_listen_in_it() throws Exception {
        final var inDatabase = delivered(request(TestGlobals.DB, List.of()));
        final var elsewhere = delivered(request("another_db", List.of()));

        manager.endAllForDatabase(TestGlobals.DB, ListenManager.DATABASE_DROPPED);
        runWorkerOver(inDatabase);

        assertEquals(1, frames().size(), written.toString());
        assertEndedFrame(frames().getFirst(), inDatabase, ListenManager.DATABASE_DROPPED);
        assertNotNull(manager.getRegistration(elsewhere));
    }

    @Test
    public void a_listen_ended_before_its_response_is_flushed_waits_for_markDelivered() throws Exception {
        final var listenId = listen(request(TestGlobals.DB, List.of()));

        manager.endAllForCollection(TestGlobals.DB, TestGlobals.COLL, ListenManager.COLLECTION_DROPPED);
        runWorkerOver(listenId);
        assertTrue(frames().isEmpty(), "the terminal frame must not overtake the LISTEN response itself");

        manager.markDelivered(listenId);
        runWorkerOver(listenId);

        assertEquals(1, frames().size(), written.toString());
        assertEndedFrame(frames().getFirst(), listenId, ListenManager.COLLECTION_DROPPED);
    }

    @Test
    public void a_revoked_reader_is_told_its_listen_ended() throws Exception {
        clientTracker.removeById(clientId);
        clientId = connectedClient(READER);
        final var listenId = delivered(request(TestGlobals.DB, List.of()));
        try {
            saveReader(null);
            runWorkerOver(listenId, listenId);
        } finally {
            saveReader(PermissionLevel.READ);
        }

        assertNull(manager.getRegistration(listenId));
        assertEquals(1, frames().size(), written.toString());
        assertEndedFrame(frames().getFirst(), listenId, ListenManager.ACCESS_REVOKED);
    }

    @Test
    public void stop_listen_and_disconnect_stay_silent() throws Exception {
        final var stopped = delivered(request(TestGlobals.DB, List.of()));
        final var undelivered = listen(request(TestGlobals.DB, List.of()));

        assertTrue(manager.unregister(stopped, clientId));
        manager.endAllForCollection(TestGlobals.DB, TestGlobals.COLL, ListenManager.COLLECTION_DROPPED);
        manager.unregisterAllForClient(clientId);
        manager.markDelivered(undelivered);
        runWorkerOver(stopped, undelivered);

        assertTrue(frames().isEmpty(), written.toString());
    }

    @Test
    public void a_gone_client_is_skipped_without_error() throws Exception {
        final var listenId = delivered(request(TestGlobals.DB, List.of()));
        clientTracker.removeById(clientId);

        manager.endAllForCollection(TestGlobals.DB, TestGlobals.COLL, ListenManager.COLLECTION_DROPPED);
        runWorkerOver(listenId);

        assertTrue(frames().isEmpty());
        assertNull(manager.getRegistration(listenId));
    }

    @Test
    public void an_update_already_produced_is_written_before_the_terminal_frame() throws Exception {
        final var listenId = manager.register(clientId, request(TestGlobals.DB, List.of()), "stale-hash");
        manager.markDelivered(listenId);
        runWorkerOver(listenId);

        manager.endAllForCollection(TestGlobals.DB, TestGlobals.COLL, ListenManager.COLLECTION_DROPPED);
        runWorkerOver(listenId);

        assertEquals(2, frames().size(), written.toString());
        assertTrue(frames().getFirst().contains("\"isUpdate\":true"), frames().getFirst());
        assertEndedFrame(frames().get(1), listenId, ListenManager.COLLECTION_DROPPED);
    }
}
