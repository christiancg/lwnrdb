package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
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

    @Test
    public void sameHash_registrationStays() throws Exception {
        final var clientId = UUID.randomUUID();
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
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

        assertNotNull(manager.getRegistration(listenId));
    }

    @Test
    public void nullWriter_registrationIsUnregistered() throws Exception {
        final var clientId = UUID.randomUUID();
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
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

        assertNull(manager.getRegistration(listenId));
    }

    // ListenProcessorThread resolves the ClientTracker fresh at construction, so callers must swap
    // before constructing the thread under test and restore right after.
    @SuppressWarnings("unchecked")
    private static ClientTracker swapClientTracker(ClientTracker replacement) throws Exception {
        final var instanceField = IocContainer.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        final var instance = instanceField.get(null);
        final var dependenciesField = instance.getClass().getDeclaredField("dependencies");
        dependenciesField.setAccessible(true);
        final var dependencies = (Map<String, Object>) dependenciesField.get(instance);
        final var original = (ClientTracker) dependencies.get(ClientTracker.class.getName());
        dependencies.put(ClientTracker.class.getName(), replacement);
        return original;
    }

    private static void restoreClientTracker(ClientTracker original) throws Exception {
        swapClientTracker(original);
    }

    @Test
    public void queryRerunThrows_isCaughtAndRegistrationSurvives() throws Exception {
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL) {
            private int calls;

            @Override
            public List<BaseAggregationStep> getAggregationSteps() {
                // ListenManager.register() reads this twice; only the later call, made from processAggregation,
                // should fail, so the query re-run inside processListen throws instead of register() itself.
                calls++;
                if (calls > 2) {
                    throw new RuntimeException("boom");
                }
                return List.of();
            }
        };
        dirtyReq.setDirtyRead(true);
        final var listenId = manager.register(UUID.randomUUID(), dirtyReq, "stale-hash-for-rerun-throws-test");

        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(listenId);
        final var thread = new ListenProcessorThread(queue, manager);
        final var t = new Thread(thread);
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);
        t.interrupt();
        t.join(1000);

        assertFalse(t.isAlive());
        assertNotNull(manager.getRegistration(listenId));
    }

    @Test
    public void getRegistrationThrows_isCaughtByOuterLoop() throws Exception {
        final var manager = new ListenManager() {
            @Override
            public org.techhouse.listen.ListenRegistration getRegistration(UUID listenId) {
                throw new RuntimeException("boom");
            }
        };

        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(UUID.randomUUID());
        final var thread = new ListenProcessorThread(queue, manager);
        final var t = new Thread(thread);
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);
        t.interrupt();
        t.join(1000);

        assertFalse(t.isAlive());
    }

    // The CAS-loses-the-race branch is deliberately uncovered: compareAndSet is final and cannot be
    // forced to fail deterministically, and racing real threads for it would only add a flaky test.

    @Test
    public void writerLockNull_registrationIsUnregistered() throws Exception {
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
        final var listenId = manager.register(UUID.randomUUID(), dirtyReq, "stale-hash-for-lock-null-test");

        final var stubWriter = new BufferedWriter(new StringWriter());
        final var stub = new ClientTracker() {
            @Override
            public BufferedWriter getWriter(UUID clientId) {
                return stubWriter;
            }

            @Override
            public java.util.concurrent.locks.ReentrantLock getWriterLock(UUID clientId) {
                return null;
            }
        };

        final var original = swapClientTracker(stub);
        final ListenProcessorThread thread;
        try {
            final var queue = new LinkedBlockingQueue<UUID>();
            queue.offer(listenId);
            thread = new ListenProcessorThread(queue, manager);
        } finally {
            restoreClientTracker(original);
        }

        final var t = new Thread(thread);
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);
        t.interrupt();
        t.join(1000);

        assertNull(manager.getRegistration(listenId));
    }

    @Test
    public void hashChanged_pushesUpdateToWriter() throws Exception {
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
        final var listenId = manager.register(UUID.randomUUID(), dirtyReq, "stale-hash-for-push-test");

        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient("listen-processor-test-user");
        final var stringWriter = new StringWriter();
        final var bufferedWriter = new BufferedWriter(stringWriter);
        clientTracker.registerWriter(clientId, bufferedWriter);
        final var registration = manager.getRegistration(listenId);
        final var repointed = new org.techhouse.listen.ListenRegistration(listenId, clientId, dirtyReq,
                registration.collectionKeys(), registration.lastHash());
        final var manager2 = new ListenManager() {
            @Override
            public org.techhouse.listen.ListenRegistration getRegistration(UUID id) {
                return id.equals(listenId) ? repointed : super.getRegistration(id);
            }

            @Override
            public boolean unregister(UUID id) {
                return manager.unregister(id);
            }
        };

        try {
            final var queue = new LinkedBlockingQueue<UUID>();
            queue.offer(listenId);
            final var thread = new ListenProcessorThread(queue, manager2);
            final var t = new Thread(thread);
            t.setDaemon(true);
            t.start();
            Thread.sleep(300);
            t.interrupt();
            t.join(1000);

            final var expectedHash = ResultHasher.hash(List.of());
            assertEquals(expectedHash, registration.lastHash().get());
            final var written = stringWriter.toString();
            assertTrue(written.contains(listenId.toString()));
        } finally {
            clientTracker.removeById(clientId);
        }
    }

    @Test
    public void writeFails_unregistersAndReleasesLock() throws Exception {
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
        final var listenId = manager.register(UUID.randomUUID(), dirtyReq, "stale-hash-for-write-fail-test");

        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient("listen-processor-test-user-2");
        final var throwingWriter = new BufferedWriter(new Writer() {
            @Override
            public void write(char @NonNull [] cbuf, int off, int len) throws IOException {
                throw new IOException("boom");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        clientTracker.registerWriter(clientId, throwingWriter);
        final var registration = manager.getRegistration(listenId);
        final var repointed = new org.techhouse.listen.ListenRegistration(listenId, clientId, dirtyReq,
                registration.collectionKeys(), registration.lastHash());
        final var manager2 = new ListenManager() {
            @Override
            public org.techhouse.listen.ListenRegistration getRegistration(UUID id) {
                return id.equals(listenId) ? repointed : super.getRegistration(id);
            }

            @Override
            public boolean unregister(UUID id) {
                return manager.unregister(id);
            }
        };

        try {
            final var queue = new LinkedBlockingQueue<UUID>();
            queue.offer(listenId);
            final var thread = new ListenProcessorThread(queue, manager2);
            final var t = new Thread(thread);
            t.setDaemon(true);
            t.start();
            Thread.sleep(300);
            t.interrupt();
            t.join(1000);

            assertNull(manager.getRegistration(listenId));
            final var writerLock = clientTracker.getWriterLock(clientId);
            assertTrue(writerLock.tryLock());
            writerLock.unlock();
        } finally {
            clientTracker.removeById(clientId);
        }
    }
}
