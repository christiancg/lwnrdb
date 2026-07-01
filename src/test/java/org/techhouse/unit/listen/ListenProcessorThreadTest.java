package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenProcessorThreadTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    // Enqueuing an unknown listenId is a no-op (registration is null)
    @Test
    public void processUnknownListenId_doesNotThrow() throws InterruptedException {
        final var manager = new ListenManager();
        final var queue = new LinkedBlockingQueue<UUID>();
        final var thread = new ListenProcessorThread(queue, manager);
        final var unknown = UUID.randomUUID();
        queue.offer(unknown);

        final var t = new Thread(thread);
        t.setDaemon(true);
        t.start();
        Thread.sleep(200);
        t.interrupt();
        t.join(1000);
    }

    // When hash hasn't changed, the registration is NOT removed
    @Test
    public void sameHash_registrationStays() throws Exception {
        final var clientId = UUID.randomUUID();
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
        // Compute the real empty-collection hash so it matches the re-run
        final var hash = ResultHasher.hash(List.of());
        final var listenId = manager.register(clientId, dirtyReq, hash);

        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(listenId);
        final var thread = new ListenProcessorThread(queue, manager);
        final var t = new Thread(thread);
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);
        t.interrupt();
        t.join(1000);

        // Hash didn't change → registration should still be present
        assertNotNull(manager.getRegistration(listenId));
    }

    // When writer is null (client disconnected), the registration is removed
    @Test
    public void nullWriter_registrationIsUnregistered() throws Exception {
        final var clientId = UUID.randomUUID();
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
        // Use a stale hash so the thread proceeds past the hash check and tries to push
        final var listenId = manager.register(clientId, dirtyReq, "stale-hash-that-will-not-match");

        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(listenId);
        final var thread = new ListenProcessorThread(queue, manager);
        final var t = new Thread(thread);
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);
        t.interrupt();
        t.join(1000);

        // No writer registered for clientId → thread should have unregistered the listen
        assertNull(manager.getRegistration(listenId));
    }
}
