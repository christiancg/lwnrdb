package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorCleanupTest {
    private static final String USERNAME = "cleanup_admin";

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var createReq = new CreateUserRequest();
        createReq.setUsername(USERNAME);
        createReq.setPassword("password123");
        createReq.setAdmin(true);
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static Socket mockSocket(InputStream in, OutputStream out) throws Exception {
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        when(socket.getInputStream()).thenReturn(in);
        when(socket.getOutputStream()).thenReturn(out);
        return socket;
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
        final var socket = mockSocket(new ByteArrayInputStream(messages.getBytes(StandardCharsets.UTF_8)),
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
}
