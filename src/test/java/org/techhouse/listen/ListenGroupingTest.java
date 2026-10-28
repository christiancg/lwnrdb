package org.techhouse.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenGroupingTest {
    private static final String LISTENER = "listen-grouping-admin";
    private static final String READER = "listen-grouping-reader";
    private static final String STALE_HASH = "a-hash-the-rerun-will-not-match";

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final List<UUID> clients = new ArrayList<>();
    private final HashMap<UUID, ByteArrayOutputStream> sinks = new HashMap<>();
    private ListenManager manager;

    @BeforeAll
    static void setUpAll() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
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
    }

    @AfterEach
    void tearDown() throws Exception {
        clients.forEach(clientTracker::removeById);
        saveReader(PermissionLevel.READ);
    }

    private static void saveReader(PermissionLevel level) throws Exception {
        final var databasePermissions = new HashMap<String, PermissionLevel>();
        if (level != null) {
            databasePermissions.put(TestGlobals.DB, level);
        }
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry(READER, "unused", false, new HashSet<>(), databasePermissions, new HashMap<>()));
    }

    private UUID client(String username) {
        final var id = clientTracker.registerForwardedClient(username);
        final var sink = new ByteArrayOutputStream();
        clientTracker.registerOutbound(id, TestUtils.inlineOutbound(sink));
        clients.add(id);
        sinks.put(id, sink);
        return id;
    }

    private List<String> frames(UUID clientId) {
        return sinks.get(clientId).toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static AggregateRequest request() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        request.setDirtyRead(true);
        return request;
    }

    private UUID listen(UUID clientId, String hash) {
        return manager.register(clientId, request(), hash);
    }

    private UUID delivered(UUID clientId, String hash) {
        final var listenId = listen(clientId, hash);
        manager.markDelivered(listenId);
        return listenId;
    }

    private static String currentHash() {
        return ResultHasher.hash(List.of(), false);
    }

    @SuppressWarnings("unchecked")
    private LinkedBlockingQueue<UUID> queue() throws Exception {
        return (LinkedBlockingQueue<UUID>) TestUtils.getPrivateField(manager, "dirtyQueue", LinkedBlockingQueue.class);
    }

    @SuppressWarnings("BusyWait")
    private void drain() throws Exception {
        final var queue = queue();
        final var worker = new Thread(new ListenProcessorThread(queue, manager));
        worker.setDaemon(true);
        worker.start();
        for (var i = 0; i < 100 && !queue.isEmpty(); i++) {
            Thread.sleep(10);
        }
        Thread.sleep(200);
        worker.interrupt();
        worker.join(1000);
    }

    @Test
    public void identicalRegistrationsQueueOneWorkItem() throws Exception {
        final var first = delivered(client(LISTENER), STALE_HASH);
        final var second = delivered(client(LISTENER), STALE_HASH);
        queue().clear();
        manager.dequeued(manager.getRegistration(first).groupId());

        manager.markDirty(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(manager.getRegistration(first).groupId(), manager.getRegistration(second).groupId());
        assertEquals(List.of(manager.getRegistration(first).groupId()), List.copyOf(queue()));
    }

    @Test
    public void distinctStepsOrUsersQueueSeparately() throws Exception {
        final var clientId = client(LISTENER);
        final var limited = request();
        limited.setAggregationSteps(List.of(new LimitAggregationStep(1)));
        final var base = manager.register(clientId, LISTENER, request(), STALE_HASH, 0);
        final var otherSteps = manager.register(clientId, LISTENER, limited, STALE_HASH, 0);
        final var otherUser = manager.register(clientId, READER, request(), STALE_HASH, 0);

        manager.markDirty(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(3, manager.groupCount());
        assertEquals(3, queue().size());
        assertNotEquals(manager.getRegistration(base).groupId(), manager.getRegistration(otherSteps).groupId());
        assertNotEquals(manager.getRegistration(base).groupId(), manager.getRegistration(otherUser).groupId());
    }

    @Test
    public void everyStaleMemberGetsItsOwnFrame() throws Exception {
        final var a = client(LISTENER);
        final var b = client(LISTENER);
        final var listenA = delivered(a, STALE_HASH);
        final var listenB = delivered(b, "another-stale-hash");

        drain();

        assertEquals(1, frames(a).size(), frames(a).toString());
        assertEquals(1, frames(b).size(), frames(b).toString());
        assertTrue(frames(a).getFirst().contains(listenA.toString()));
        assertTrue(frames(b).getFirst().contains(listenB.toString()));
        assertEquals(currentHash(), manager.getRegistration(listenA).lastHash().get());
        assertEquals(currentHash(), manager.getRegistration(listenB).lastHash().get());
    }

    @Test
    public void aMemberAlreadyAtTheNewHashIsNotPushed() throws Exception {
        final var stale = client(LISTENER);
        final var current = client(LISTENER);
        delivered(stale, STALE_HASH);
        delivered(current, currentHash());

        drain();

        assertEquals(1, frames(stale).size());
        assertEquals(List.of(), frames(current));
    }

    @Test
    public void anUndeliveredMemberIsSkippedThenComparedOnDelivery() throws Exception {
        final var a = client(LISTENER);
        final var b = client(LISTENER);
        delivered(a, STALE_HASH);
        final var undelivered = listen(b, STALE_HASH);

        drain();

        assertEquals(1, frames(a).size());
        assertEquals(List.of(), frames(b), "nothing may reach a client before its LISTEN response");
        assertEquals(STALE_HASH, manager.getRegistration(undelivered).lastHash().get());

        manager.markDelivered(undelivered);
        drain();

        assertEquals(1, frames(a).size(), "the member already at the new hash is not pushed again");
        assertEquals(1, frames(b).size());
        assertTrue(frames(b).getFirst().contains(undelivered.toString()));
    }

    @Test
    public void revokingOneMemberEndsOnlyThatMember() throws Exception {
        final var admin = client(LISTENER);
        final var reader = client(READER);
        final var adminListen = delivered(admin, STALE_HASH);
        final var readerListen = delivered(reader, STALE_HASH);
        assertEquals(manager.getRegistration(adminListen).groupId(), manager.getRegistration(readerListen).groupId());

        saveReader(null);
        drain();

        assertNull(manager.getRegistration(readerListen));
        assertNotNull(manager.getRegistration(adminListen));
        assertEquals(1, frames(admin).size());
        assertTrue(frames(admin).getFirst().contains("\"isUpdate\":true"), frames(admin).getFirst());
        assertEquals(1, frames(reader).size(), frames(reader).toString());
        assertTrue(frames(reader).getFirst().contains(ListenManager.ACCESS_REVOKED), frames(reader).getFirst());
    }

    @Test
    public void theLastMemberLeavingDropsTheGroup() {
        final var clientId = client(LISTENER);
        final var first = listen(clientId, STALE_HASH);
        final var second = listen(clientId, STALE_HASH);
        final var ended = listen(clientId, STALE_HASH);
        assertEquals(1, manager.groupCount());

        manager.unregister(first);
        manager.end(ended, ListenManager.COLLECTION_DROPPED);
        assertEquals(1, manager.groupCount());

        manager.unregister(second);
        assertEquals(0, manager.groupCount());
    }

    @Test
    public void aRegistrationJoiningReusesTheGroupRequest() {
        final var clientId = client(LISTENER);
        final var first = manager.getRegistration(listen(clientId, STALE_HASH));
        final var second = manager.getRegistration(listen(clientId, STALE_HASH));

        assertSame(first.request(), second.request());
        assertSame(manager.group(first.groupId()).request(), second.request());
    }

    @Test
    public void stoppingTheWorkersForgetsEveryGroup() {
        listen(client(LISTENER), STALE_HASH);

        manager.stopWorkers();

        assertEquals(0, manager.groupCount());
    }
}
