package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.config.Configuration;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.test.TestUtils;

public class MessageProcessorTest {

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private Socket mockSocket(InputStream in, OutputStream out) throws Exception {
        Socket s = Mockito.mock(Socket.class);
        InetAddress addr = Mockito.mock(InetAddress.class);
        when(s.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        when(s.getInputStream()).thenReturn(in);
        when(s.getOutputStream()).thenReturn(out);
        return s;
    }

    // Handles null or blank messages gracefully
    @Test
    public void test_handles_null_or_blank_messages() throws Exception {
        Socket mockSocket = mockSocket(new ByteArrayInputStream("".getBytes()), new ByteArrayOutputStream());
        MessageProcessor messageProcessor = new MessageProcessor(mockSocket);
        Thread thread = new Thread(messageProcessor);
        thread.start();
        thread.join(3000);
        assertFalse(thread.isAlive());
    }

    // A valid message is processed and a JSON response is written back
    @Test
    public void test_valid_message_is_processed_and_response_written() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String msg = "{\"type\":\"LIST_DATABASES\"}\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        String response = out.toString();
        assertTrue(response.contains("LIST_DATABASES"), "Response should echo operation type");
        assertTrue(response.contains("OK"), "Response should indicate success");
    }

    // A CLOSE_CONNECTION message causes the processing loop to exit cleanly
    @Test
    public void test_close_connection_message_exits_loop() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String msg = "{\"type\":\"CLOSE_CONNECTION\"}\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        assertFalse(t.isAlive(), "Thread should have exited after CLOSE_CONNECTION");
        assertTrue(out.toString().contains("CLOSE_CONNECTION"));
    }

    // An invalid JSON message causes the InvalidCommandException message to be written
    @Test
    public void test_invalid_json_responds_with_exception_message() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String msg = "not_valid_json\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        String response = out.toString();
        assertFalse(response.isEmpty(), "An error response should have been written");
    }

    // When max connections is reached, clientId is null and an error response is sent (L63-67)
    @Test
    public void test_max_connections_sends_error_response() throws Exception {
        // Set maxConnections to a value the client count can never be under so any
        // new connection gets a null clientId (0 now means unlimited, so use -1).
        Configuration config = Configuration.getInstance();
        int originalMax = config.getMaxConnections();
        try {
            TestUtils.setPrivateField(config, "maxConnections", -1);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Socket socket = mockSocket(new ByteArrayInputStream("".getBytes()), out);

            MessageProcessor mp = new MessageProcessor(socket);
            Thread t = new Thread(mp);
            t.start();
            t.join(3000);

            String response = out.toString();
            assertTrue(response.contains("CLOSE_CONNECTION"), "Should send CLOSE_CONNECTION on max connections");
            assertTrue(response.contains("ERROR"), "Should include ERROR status");
        } finally {
            TestUtils.setPrivateField(config, "maxConnections", originalMax);
        }
    }

    // A request that fails validation returns an ERROR response without reaching the processor
    @Test
    public void test_invalid_request_validation_fails_sends_error_response() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // SAVE with a 2-char databaseName violates the naming rule
        String msg = "{\"type\":\"SAVE\",\"databaseName\":\"ab\",\"collectionName\":\"myColl\",\"object\":{}}\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        String response = out.toString();
        assertTrue(response.contains("ERROR"), "Validation failure should return ERROR status");
        assertTrue(response.contains("SAVE"), "Response type should echo the operation type");
    }

    // Unauthenticated protected request returns UNAUTHENTICATED
    @Test
    public void test_unauthenticated_request_returns_unauthenticated() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String msg = "{\"type\":\"SAVE\",\"databaseName\":\"testDb\",\"collectionName\":\"testColl\",\"object\":{}}\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        String response = out.toString();
        assertTrue(response.contains("UNAUTHENTICATED"), "Should return UNAUTHENTICATED for protected op");
    }

    // Authenticate then send a protected request — should be allowed
    @Test
    public void test_authenticated_request_is_processed() throws Exception {
        // Create an admin user first
        final var createReq = new CreateUserRequest();
        createReq.setUsername("msg_proce_admin");
        createReq.setPassword("password123");
        createReq.setAdmin(true);
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Use a protected op (SAVE) to exercise the authenticated+authorized dispatch path
        String messages = """
                {"type":"AUTHENTICATE","username":"msg_proce_admin","password":"password123"}
                {"type":"SAVE","databaseName":"testDb","collectionName":"testColl","object":{"name":"test"}}
                """;
        Socket socket = mockSocket(new ByteArrayInputStream(messages.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        String response = out.toString();
        assertTrue(response.contains("AUTHENTICATE"), "Should include AUTHENTICATE response");
        assertTrue(response.contains("SAVE"), "Should include SAVE response");
    }

    // Authenticate then CLOSE_CONNECTION from the authenticated path
    @Test
    public void test_authenticated_close_connection() throws Exception {
        final var createReq = new CreateUserRequest();
        createReq.setUsername("msg_closer");
        createReq.setPassword("password123");
        createReq.setAdmin(true);
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String messages = """
                {"type":"AUTHENTICATE","username":"msg_closer","password":"password123"}
                {"type":"CLOSE_CONNECTION"}
                """;
        Socket socket = mockSocket(new ByteArrayInputStream(messages.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        assertFalse(t.isAlive(), "Thread should exit after CLOSE_CONNECTION");
    }

    // Authenticated but forbidden request returns FORBIDDEN
    @Test
    public void test_authenticated_forbidden_request_returns_forbidden() throws Exception {
        // Create a non-admin user with no permissions
        final var createReq = new CreateUserRequest();
        createReq.setUsername("noPermsUser");
        createReq.setPassword("password123");
        createReq.setAdmin(false);
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String messages = """
                {"type":"AUTHENTICATE","username":"noPermsUser","password":"password123"}
                {"type":"SAVE","databaseName":"testDb","collectionName":"testColl","object":{}}
                """;
        Socket socket = mockSocket(new ByteArrayInputStream(messages.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        String response = out.toString();
        assertTrue(response.contains("FORBIDDEN"), "Should return FORBIDDEN for unauthorized op");
    }

    // An IOException on the output stream is handled without crashing
    @Test
    public void test_ioexception_on_output_stream_is_handled() throws Exception {
        OutputStream throwingOut = mock(OutputStream.class);
        doThrow(new IOException("write failed")).when(throwingOut).write(any(byte[].class), anyInt(), anyInt());
        String msg = "{\"type\":\"LIST_DATABASES\"}\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), throwingOut);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        assertFalse(t.isAlive(), "Thread should exit after IOException");
    }
}
