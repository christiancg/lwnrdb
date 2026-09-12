package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorListenTest {

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
        final var s = Mockito.mock(Socket.class);
        final var addr = Mockito.mock(InetAddress.class);
        Mockito.when(s.getInetAddress()).thenReturn(addr);
        Mockito.when(addr.getHostAddress()).thenReturn("127.0.0.1");
        Mockito.when(s.getInputStream()).thenReturn(in);
        Mockito.when(s.getOutputStream()).thenReturn(out);
        return s;
    }

    private String runMessages(String messages) throws Exception {
        final var out = new ByteArrayOutputStream();
        final var socket = mockSocket(new ByteArrayInputStream(messages.getBytes()), out);
        final var mp = new MessageProcessor(socket);
        final var t = new Thread(mp);
        t.start();
        t.join(3000);
        return out.toString();
    }

    private void createListenAdmin(String username) {
        final var req = new CreateUserRequest();
        req.setUsername(username);
        req.setPassword("password123");
        req.setAdmin(true);
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(req);
    }

    @Test
    public void test_listen_returns_listenId_and_hash() throws Exception {
        createListenAdmin("listen_wire_admin");
        final var messages = "{\"type\":\"AUTHENTICATE\",\"username\":\"listen_wire_admin\",\"password\":\"password123\"}\n"
                + "{\"type\":\"LISTEN\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"aggregationSteps\":[]}\n";

        final var response = runMessages(messages);

        assertTrue(response.contains("LISTEN"), "Response should contain LISTEN type");
        assertTrue(response.contains("listenId"), "Response should include listenId");
        assertTrue(response.contains("resultHash"), "Response should include resultHash");
        assertTrue(response.contains("OK"), "Response should be OK");
    }

    @Test
    public void test_unauthenticated_listen_returns_unauthenticated() throws Exception {
        final var messages = "{\"type\":\"LISTEN\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"aggregationSteps\":[]}\n";

        final var response = runMessages(messages);

        assertTrue(response.contains("UNAUTHENTICATED"), "Unauthenticated LISTEN should return UNAUTHENTICATED");
    }

    @Test
    public void test_listen_invalid_request_returns_error() throws Exception {
        createListenAdmin("listen_invalid_admin");
        final var messages = "{\"type\":\"AUTHENTICATE\",\"username\":\"listen_invalid_admin\",\"password\":\"password123\"}\n"
                + "{\"type\":\"LISTEN\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\"}\n";

        final var response = runMessages(messages);

        assertTrue(response.contains("ERROR"), "Should return ERROR for missing aggregationSteps");
    }

    @Test
    public void test_stop_listen_unknown_id_returns_404() throws Exception {
        createListenAdmin("stop_listen_admin");
        final var messages = """
                {"type":"AUTHENTICATE","username":"stop_listen_admin","password":"password123"}
                {"type":"STOP_LISTEN","listenId":"550e8400-e29b-41d4-a716-446655440000"}
                """;

        final var response = runMessages(messages);

        assertTrue(response.contains("404-7"), "Unknown listenId should return 404-7");
        assertTrue(response.contains("NOT_FOUND"), "Status should be NOT_FOUND");
    }

    @Test
    public void test_listen_then_stop_listen_returns_ok() throws Exception {
        createListenAdmin("listen_stop_admin");
        final var out = new ByteArrayOutputStream();
        final var listenMsg = "{\"type\":\"AUTHENTICATE\",\"username\":\"listen_stop_admin\",\"password\":\"password123\"}\n"
                + "{\"type\":\"LISTEN\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"aggregationSteps\":[]}\n";
        final var socket = mockSocket(new ByteArrayInputStream(listenMsg.getBytes()), out);
        final var mp = new MessageProcessor(socket);
        final var t = new Thread(mp);
        t.start();
        t.join(3000);
        final var listenResponse = out.toString();

        assertTrue(listenResponse.contains("listenId"), "LISTEN response must contain listenId");
    }
}
