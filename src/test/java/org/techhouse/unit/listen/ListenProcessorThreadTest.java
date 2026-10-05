package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenProcessorThreadTest {

    private static final String LISTENER = "listen-processor-admin";
    private static final String READER = "listen-processor-reader";
    private UUID lastListenId;

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry(LISTENER, "unused", true, new HashSet<>(), new HashMap<>(), new HashMap<>()));
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

    private static UUID authenticatedClient() {
        return IocContainer.get(ClientTracker.class).registerForwardedClient(LISTENER);
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
        final var clientId = authenticatedClient();
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
        final var hash = ResultHasher.hash(List.of(), false);
        final var listenId = registerDelivered(manager, clientId, dirtyReq, hash);

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
        final var clientId = authenticatedClient();
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
        final var listenId = registerDelivered(manager, clientId, dirtyReq, "stale-hash-that-will-not-match");

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
        final var listenId = registerDelivered(manager, authenticatedClient(), dirtyReq,
                "stale-hash-for-rerun-throws-test");

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
        final var listenId = registerDelivered(manager, UUID.randomUUID(), dirtyReq, "stale-hash-for-lock-null-test");

        final var stubWriter = new BufferedWriter(new StringWriter());
        final var stub = new ClientTracker() {
            @Override
            public String getAuthenticatedUsername(UUID clientId) {
                return LISTENER;
            }

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
        final var listenId = registerDelivered(manager, UUID.randomUUID(), dirtyReq, "stale-hash-for-push-test");

        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient(LISTENER);
        final var stringWriter = new StringWriter();
        final var bufferedWriter = new BufferedWriter(stringWriter);
        clientTracker.registerWriter(clientId, bufferedWriter);
        final var registration = manager.getRegistration(listenId);
        final var repointed = new org.techhouse.listen.ListenRegistration(listenId, clientId, dirtyReq,
                registration.collectionKeys(), registration.lastHash(), registration.delivered());
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

            final var expectedHash = ResultHasher.hash(List.of(), false);
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
        final var listenId = registerDelivered(manager, UUID.randomUUID(), dirtyReq, "stale-hash-for-write-fail-test");

        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient(LISTENER);
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
                registration.collectionKeys(), registration.lastHash(), registration.delivered());
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

    @Test
    public void readerWithAccess_keepsTheRegistration() throws Exception {
        saveReader(PermissionLevel.READ);
        final var clientId = IocContainer.get(ClientTracker.class).registerForwardedClient(READER);
        try {
            assertNotNull(runOnce(registerUnchanged(clientId)).getRegistration(lastListenId));
        } finally {
            IocContainer.get(ClientTracker.class).removeById(clientId);
        }
    }

    @Test
    public void readerWhosePermissionWasRevoked_isUnregistered() throws Exception {
        saveReader(PermissionLevel.READ);
        final var clientId = IocContainer.get(ClientTracker.class).registerForwardedClient(READER);
        try {
            final var manager = registerUnchanged(clientId);
            saveReader(null);
            assertNull(runOnce(manager).getRegistration(lastListenId));
        } finally {
            saveReader(PermissionLevel.READ);
            IocContainer.get(ClientTracker.class).removeById(clientId);
        }
    }

    @Test
    public void readerWhoWasDeleted_isUnregistered() throws Exception {
        saveReader(PermissionLevel.READ);
        final var clientId = IocContainer.get(ClientTracker.class).registerForwardedClient(READER);
        try {
            final var manager = registerUnchanged(clientId);
            AdminOperationHelper.deleteUserEntry(READER);
            assertNull(runOnce(manager).getRegistration(lastListenId));
        } finally {
            saveReader(PermissionLevel.READ);
            IocContainer.get(ClientTracker.class).removeById(clientId);
        }
    }

    @Test
    public void readerWhoWasDeletedAndRecreated_isUnregistered() throws Exception {
        saveReader(PermissionLevel.READ);
        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var socket = org.mockito.Mockito.mock(java.net.Socket.class);
        final var address = org.mockito.Mockito.mock(java.net.InetAddress.class);
        org.mockito.Mockito.when(socket.getInetAddress()).thenReturn(address);
        org.mockito.Mockito.when(address.getHostAddress()).thenReturn("127.0.0.1");
        TestUtils.setPrivateField(org.techhouse.config.Configuration.getInstance(), "maxConnections", 0);
        final var clientId = clientTracker.addClient(socket);
        clientTracker.setAuthenticatedUser(clientId, READER);
        try {
            final var manager = registerUnchanged(clientId);
            AdminOperationHelper.deleteUserEntry(READER);
            saveReader(PermissionLevel.READ);
            assertNull(runOnce(manager).getRegistration(lastListenId),
                    "a listener must not keep pushing to a user created later under the same name");
        } finally {
            saveReader(PermissionLevel.READ);
            clientTracker.removeById(clientId);
        }
    }

    @Test
    public void unauthenticatedClient_isUnregistered() throws Exception {
        assertNull(runOnce(registerUnchanged(UUID.randomUUID())).getRegistration(lastListenId));
    }

    private ListenManager registerUnchanged(UUID clientId) {
        final var manager = new ListenManager();
        final var dirtyReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        dirtyReq.setAggregationSteps(List.of());
        dirtyReq.setDirtyRead(true);
        lastListenId = registerDelivered(manager, clientId, dirtyReq, ResultHasher.hash(List.of(), false));
        return manager;
    }

    private static UUID registerDelivered(ListenManager manager, UUID clientId, AggregateRequest request,
            String initialHash) {
        final var listenId = manager.register(clientId, request, initialHash);
        manager.markDelivered(listenId);
        return listenId;
    }

    private ListenManager runOnce(ListenManager manager) throws InterruptedException {
        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(lastListenId);
        final var t = new Thread(new ListenProcessorThread(queue, manager));
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);
        t.interrupt();
        t.join(1000);
        return manager;
    }
}
