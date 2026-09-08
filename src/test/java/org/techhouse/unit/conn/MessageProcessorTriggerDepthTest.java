package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * A client must not be able to claim a cascade depth: only EnforcingDatabaseAccess (a running trigger) sets
 * one, so whatever arrives on the wire is discarded at the edge.
 */
public class MessageProcessorTriggerDepthTest {
    private static final String ADMIN = "depthadmin";
    private static final String PASSWORD = "password123";
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final CopyOnWriteArrayList<TriggerEvent> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(Configuration.getInstance(), "triggersEnabled", true);
        final var request = new CreateUserRequest();
        request.setUsername(ADMIN);
        request.setPassword(PASSWORD);
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
        // allowCascade is on, so a depth > 0 would fire - which is what makes the zeroing observable.
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit",
                        new LinkedHashSet<>(Set.of(EventType.CREATED, EventType.UPDATED)), "recalc",
                        TriggerDefinition.MODE_DOCUMENT, true, true, "owner", 1L, 1L, 1L, "owner")));
        captured.clear();
        triggerExecutor.stop();
        triggerExecutor.start(captured::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        triggerExecutor.stop();
        TestUtils.setPrivateField(Configuration.getInstance(), "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Socket mockSocket(InputStream in, OutputStream out) throws Exception {
        final var socket = Mockito.mock(Socket.class);
        final var address = Mockito.mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        when(socket.getInputStream()).thenReturn(in);
        when(socket.getOutputStream()).thenReturn(out);
        return socket;
    }

    private List<String> exchange(String... requests) throws Exception {
        final var out = new ByteArrayOutputStream();
        final var message = String.join("\n", requests) + "\n";
        final var socket = mockSocket(new ByteArrayInputStream(message.getBytes()), out);
        final var thread = new Thread(new MessageProcessor(socket));
        thread.start();
        thread.join(10000);
        return List.of(out.toString().split("\n"));
    }

    private List<TriggerEvent> settle() {
        for (int i = 0; i < 100 && captured.isEmpty(); i++) {
            sleep(5);
        }
        sleep(30);
        return List.copyOf(captured);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void test_client_supplied_trigger_depth_is_zeroed() throws Exception {
        final var responses = exchange(
                "{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN + "\",\"password\":\"" + PASSWORD + "\"}",
                "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                        + TestGlobals.COLL + "\",\"triggerDepth\":99,\"object\":{\"_id\":\"spoofed\",\"v\":1}}");
        assertTrue(responses.get(1).contains("\"status\":\"OK\""), responses.get(1));
        final var events = settle();
        assertEquals(1, events.size());
        assertEquals(0, events.getFirst().getDepth(), "a client-claimed depth must be discarded at the edge");
    }

    @Test
    public void test_an_ordinary_write_is_depth_zero() throws Exception {
        exchange("{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN + "\",\"password\":\"" + PASSWORD + "\"}",
                "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                        + TestGlobals.COLL + "\",\"object\":{\"_id\":\"plain\",\"v\":1}}");
        assertEquals(0, settle().getFirst().getDepth());
    }
}
