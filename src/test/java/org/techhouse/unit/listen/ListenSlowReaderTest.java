package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenSlowReaderTest {
    private static final String LISTENER = "listen-slow-reader-admin";
    private static final String STALE_HASH = "hash-the-rerun-will-not-match";

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final CountDownLatch slowReaderReads = new CountDownLatch(1);
    private final CountDownLatch slowWriteStarted = new CountDownLatch(1);
    private final StringWriter fastPushed = new StringWriter();
    private ListenManager manager;
    private UUID slowClient;
    private UUID fastClient;
    private Thread worker;

    @BeforeAll
    static void setUpAll() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
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
        slowClient = clientTracker.registerForwardedClient(LISTENER);
        fastClient = clientTracker.registerForwardedClient(LISTENER);
        clientTracker.registerOutbound(slowClient, new OutboundQueue(new BufferedWriter(new Writer() {
            @Override
            @SuppressWarnings("NullableProblems")
            public void write(char[] buffer, int offset, int length) {
                slowWriteStarted.countDown();
                try {
                    slowReaderReads.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        }, 1), () -> {
        }, Long.MAX_VALUE, OutboundQueue.VIRTUAL_DRAINER, () -> {
        }));
        clientTracker.registerOutbound(fastClient, TestUtils.inlineOutbound(new BufferedWriter(fastPushed)));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        slowReaderReads.countDown();
        if (worker != null) {
            worker.interrupt();
            worker.join(1000);
        }
        clientTracker.removeById(slowClient);
        clientTracker.removeById(fastClient);
    }

    private static AggregateRequest dirtyRequest() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        request.setDirtyRead(true);
        return request;
    }

    @SuppressWarnings("unchecked")
    private LinkedBlockingQueue<UUID> managerQueue() throws Exception {
        return (LinkedBlockingQueue<UUID>) TestUtils.getPrivateField(manager, "dirtyQueue", LinkedBlockingQueue.class);
    }

    @SuppressWarnings("BusyWait")
    private boolean fastClientReceived(UUID listenId) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (fastPushed.toString().contains(listenId.toString())) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    public void test_a_slow_reader_does_not_block_other_clients() throws Exception {
        final var slowListen = manager.register(slowClient, dirtyRequest(), STALE_HASH);
        final var fastListen = manager.register(fastClient, dirtyRequest(), STALE_HASH);
        manager.markDelivered(slowListen);
        manager.markDelivered(fastListen);

        worker = Thread.ofPlatform().daemon().start(new ListenProcessorThread(managerQueue(), manager));

        assertTrue(slowWriteStarted.await(5, TimeUnit.SECONDS), "the slow client's push reached its socket");
        assertTrue(fastClientReceived(fastListen), "the worker moved on while the slow client was not reading");
        assertEquals(1, slowReaderReads.getCount(), "the slow client never read during the test");
    }
}
