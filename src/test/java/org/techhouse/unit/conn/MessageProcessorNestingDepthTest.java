package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorNestingDepthTest {
    private static final String USERNAME = "nesting_admin";
    private static final String TOO_DEEP = "{\"a\":".repeat(Globals.MAX_REQUEST_NESTING_DEPTH * 20) + "1"
            + "}".repeat(Globals.MAX_REQUEST_NESTING_DEPTH * 20);

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

    private static String saveOf(String id, String object) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"" + id + "\",\"n\":" + object + "}}\n";
    }

    private static List<String> converse(String messages) throws Exception {
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        final var out = new ByteArrayOutputStream();
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(messages.getBytes(StandardCharsets.UTF_8)));
        when(socket.getOutputStream()).thenReturn(out);
        final var thread = new Thread(new MessageProcessor(socket));
        thread.start();
        thread.join(20000);
        assertFalse(thread.isAlive(), "the connection thread must finish at end of input");
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static String authenticate() {
        return "{\"type\":\"AUTHENTICATE\",\"username\":\"" + USERNAME + "\",\"password\":\"password123\"}\n";
    }

    private static OperationStatus statusOf(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return IocContainer.get(OperationProcessor.class).processMessage(request).getStatus();
    }

    @Test
    public void deeply_nested_request_is_answered_and_the_connection_stays_open() throws Exception {
        final var responses = converse(authenticate() + saveOf("deep", TOO_DEEP) + saveOf("after", "1"));

        assertEquals(3, responses.size(), "every request, including the refused one, gets exactly one answer");
        assertFalse(responses.get(1).contains("\"status\":\"OK\""));
        assertTrue(responses.get(2).contains("\"status\":\"OK\""));
        assertEquals(OperationStatus.OK, statusOf("after"));
    }

    @Test
    public void open_transaction_survives_a_refused_deep_request() throws Exception {
        final var responses = converse(authenticate() + "{\"type\":\"START_TRANSACTION\"}\n" + saveOf("in-tx", "1")
                + saveOf("deep", TOO_DEEP) + "{\"type\":\"COMMIT_TRANSACTION\"}\n");

        assertEquals(5, responses.size());
        assertTrue(responses.get(4).contains("\"status\":\"OK\""), responses.get(4));
        assertEquals(OperationStatus.OK, statusOf("in-tx"));
    }
}
