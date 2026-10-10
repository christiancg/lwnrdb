package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorDeletedUserTest {
    private static final String USERNAME = "doomed_writer";
    private static final String PASSWORD = "password123";

    private final PipedOutputStream toServer = new PipedOutputStream();
    private final PipedInputStream fromServer = new PipedInputStream(1 << 16);
    private BufferedReader responses;
    private Thread connection;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var createReq = new CreateUserRequest();
        createReq.setUsername(USERNAME);
        createReq.setPassword(PASSWORD);
        createReq.setAdmin(true);
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        final var serverIn = new PipedInputStream(toServer, 1 << 16);
        final var serverOut = new PipedOutputStream(fromServer);
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        when(socket.getInputStream()).thenReturn(serverIn);
        when(socket.getOutputStream()).thenReturn(serverOut);
        responses = new BufferedReader(new InputStreamReader(fromServer, StandardCharsets.UTF_8));
        connection = new Thread(new MessageProcessor(socket));
        connection.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        toServer.close();
        connection.join(10000);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private String send(String message) throws Exception {
        toServer.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
        return responses.readLine();
    }

    private void openTransactionThenLoseTheUser() throws Exception {
        assertTrue(
                send("{\"type\":\"AUTHENTICATE\",\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}")
                        .contains("\"OK\""));
        assertTrue(send("{\"type\":\"START_TRANSACTION\"}").contains("\"OK\""));
        assertTrue(send("{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"object\":{\"_id\":\"held\"}}").contains("\"OK\""));
        AdminOperationHelper.deleteUserEntry(USERNAME);
    }

    private static boolean collectionIsWritable() {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var free = locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL);
        if (free) {
            locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
        }
        return free;
    }

    @Test
    public void test_rollback_succeeds_after_the_user_was_deleted() throws Exception {
        openTransactionThenLoseTheUser();

        final var answer = send("{\"type\":\"ROLLBACK_TRANSACTION\"}");

        assertTrue(answer.contains("\"OK\""), answer);
        assertTrue(collectionIsWritable(), "the rollback is what releases the connection's thread-owned locks");
    }

    @Test
    public void test_commit_is_still_refused_after_the_user_was_deleted() throws Exception {
        openTransactionThenLoseTheUser();

        final var answer = send("{\"type\":\"COMMIT_TRANSACTION\"}");

        assertFalse(answer.contains("\"OK\""), answer);
        assertFalse(collectionIsWritable());
        assertTrue(send("{\"type\":\"ROLLBACK_TRANSACTION\"}").contains("\"OK\""));
    }

    @Test
    public void test_rollback_without_a_transaction_still_needs_authentication() throws Exception {
        final var answer = send("{\"type\":\"ROLLBACK_TRANSACTION\"}");

        assertTrue(answer.contains("401-"), answer);
    }
}
