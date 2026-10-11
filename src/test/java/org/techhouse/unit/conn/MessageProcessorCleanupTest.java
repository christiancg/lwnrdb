package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorCleanupTest {
    private static final String USERNAME = "cleanup_admin";

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(USERNAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static final class ThrowingAfterSave extends OutputStream {
        private final ByteArrayOutputStream seen = new ByteArrayOutputStream();
        private final Throwable toThrow;

        private ThrowingAfterSave(Throwable toThrow) {
            this.toThrow = toThrow;
        }

        // Only the single-byte form is overridden: OutputStream's array form loops through it, so this
        // sees every byte without redeclaring a parameter the JDK annotates.
        @Override
        public void write(int b) {
            seen.write(b);
            throwIfSaveAnswered();
        }

        private void throwIfSaveAnswered() {
            if (!seen.toString(StandardCharsets.UTF_8).contains("\"type\":\"SAVE\"")) {
                return;
            }
            if (toThrow instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) toThrow;
        }
    }

    private void runUntilItDies(Throwable toThrow) throws Exception {
        final var messages = "{\"type\":\"AUTHENTICATE\",\"username\":\"" + USERNAME
                + "\",\"password\":\"password123\"}\n" + "{\"type\":\"START_TRANSACTION\"}\n"
                + "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"object\":{\"_id\":\"cleanup-1\"}}\n";
        final var socket = TestUtils.clientSocket(new ByteArrayInputStream(messages.getBytes(StandardCharsets.UTF_8)),
                new ThrowingAfterSave(toThrow));
        final var thread = new Thread(new MessageProcessor(socket));
        thread.setUncaughtExceptionHandler((ignoredThread, ignoredError) -> {
        });
        thread.start();
        thread.join(10000);
        assertFalse(thread.isAlive(), "the connection thread must not still be running");
    }

    private void assertTheConnectionLeftNothingBehind() {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var free = locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL);
        if (free) {
            locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
        }
        assertTrue(free, "the collection write lock must be released by the thread that took it");
        assertTrue(IocContainer.get(Cache.class).getTransactionPkIndexes().isEmpty(),
                "the buffered op records must be cleaned up too");
    }

    @Test
    public void test_a_runtime_exception_releases_an_open_transaction() throws Exception {
        runUntilItDies(new IllegalStateException("unexpected"));

        assertTheConnectionLeftNothingBehind();
    }

    @Test
    public void test_an_error_releases_an_open_transaction() throws Exception {
        runUntilItDies(new StackOverflowError());

        assertTheConnectionLeftNothingBehind();
    }

    @Test
    public void test_a_failing_teardown_still_removes_the_client_and_releases_its_departure() throws Exception {
        final var processor = new MessageProcessor(
                TestUtils.clientSocket(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        final var failingListens = mock(ListenManager.class);
        doThrow(new IllegalStateException("listen teardown failed")).when(failingListens).unregisterAllForClient(any());
        TestUtils.setPrivateField(processor, "listenManager", failingListens);
        final var clientId = TestUtils.getPrivateField(processor, "clientId", UUID.class);
        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var departure = clientTracker.signalDisconnect(clientId);

        assertThrows(IllegalStateException.class, processor::run);

        assertEquals(0, departure.getCount(), "shutdown must not wait out its budget on a failed teardown");
        assertFalse(clientTracker.clientIdsSnapshot().contains(clientId), "the client must not leak");
    }
}
