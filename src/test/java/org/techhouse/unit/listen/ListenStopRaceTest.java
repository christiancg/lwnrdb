package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.listen.ListenRegistration;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenStopRaceTest {
    private static final String LISTENER = "listen-stop-race-admin";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry(LISTENER, "unused", true, new HashSet<>(), new HashMap<>(), new HashMap<>()));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static final class StoppedDuringRerun extends ListenManager {
        private final AtomicInteger lookups = new AtomicInteger();
        private UUID stopped;

        @Override
        public ListenRegistration getRegistration(UUID listenId) {
            if (listenId.equals(stopped) && lookups.incrementAndGet() > 1) {
                unregister(listenId);
            }
            return super.getRegistration(listenId);
        }
    }

    private static void runOnce(ListenManager manager, UUID listenId) throws InterruptedException {
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
    public void test_an_update_is_not_pushed_after_the_listen_was_stopped() throws Exception {
        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient(LISTENER);
        final var written = new StringWriter();
        clientTracker.registerWriter(clientId, new BufferedWriter(written));
        final var manager = new StoppedDuringRerun();
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        request.setDirtyRead(true);
        final var listenId = manager.register(clientId, request, "a-hash-the-rerun-will-not-match");
        manager.markDelivered(listenId);
        manager.stopped = listenId;
        try {
            runOnce(manager, listenId);

            assertTrue(manager.lookups.get() >= 2, "the worker must look the registration up again before writing");
            assertNull(manager.getRegistration(listenId));
            assertEquals("", written.toString(),
                    "the client was told the listen stopped, so no update frame may follow that answer");
        } finally {
            clientTracker.removeById(clientId);
        }
    }
}
