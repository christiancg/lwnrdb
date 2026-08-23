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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.config.Configuration;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorRunScriptTest {
    private static final String ADMIN = "wireadmin";
    private static final String READER = "wirereader";
    private static final String NOBODY = "wirenobody";
    private static final String PASSWORD = "password123";

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var configuration = Configuration.getInstance();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxLogLines", 1_000);
        TestUtils.setPrivateField(configuration, "scriptMaxLogLineChars", 4_096);
        createUser(ADMIN, true, new HashMap<>(), new HashMap<>());
        final var readOnly = new HashMap<String, PermissionLevel>();
        readOnly.put(TestGlobals.DB, PermissionLevel.READ);
        final var scriptGrant = new HashMap<String, Boolean>();
        scriptGrant.put(TestGlobals.DB, true);
        createUser(READER, false, readOnly, scriptGrant);
        createUser(NOBODY, false, new HashMap<>(), new HashMap<>());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void createUser(String username, boolean admin, HashMap<String, PermissionLevel> databasePermissions,
            HashMap<String, Boolean> scriptPermissions) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword(PASSWORD);
        request.setAdmin(admin);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(databasePermissions);
        request.setCollectionPermissions(new HashMap<>());
        request.setScriptPermissions(scriptPermissions);
        UserOperationHelper.processCreateUser(request);
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

    private static String authenticate(String username) {
        return "{\"type\":\"AUTHENTICATE\",\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static String runScript(String script) {
        return "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"" + TestGlobals.DB + "\",\"script\":\"" + script + "\"}";
    }

    @Test
    public void test_authenticated_admin_runs_a_script() throws Exception {
        final var responses = exchange(authenticate(ADMIN), runScript("console.log('hi'); return 1 + 1;"));
        assertEquals(2, responses.size());
        final var response = responses.get(1);
        assertTrue(response.contains("\"type\":\"RUN_SCRIPT\""), response);
        assertTrue(response.contains("\"status\":\"OK\""), response);
        assertTrue(response.contains("\"result\":2"), response);
        assertTrue(response.contains("\"logs\":[\"hi\"]"), response);
    }

    @Test
    public void test_run_script_before_authenticating_is_rejected() throws Exception {
        final var responses = exchange(runScript("return 1;"));
        assertTrue(responses.getFirst().contains("\"errorCode\":\"401-1\""), responses.getFirst());
    }

    @Test
    public void test_user_without_permission_is_forbidden() throws Exception {
        final var responses = exchange(authenticate(NOBODY), runScript("return 1;"));
        assertTrue(responses.get(1).contains("\"errorCode\":\"403-1\""), responses.get(1));
    }

    @Test
    public void test_user_with_run_script_permission_is_allowed() throws Exception {
        final var responses = exchange(authenticate(READER), runScript("return 41 + 1;"));
        assertTrue(responses.get(1).contains("\"result\":42"), responses.get(1));
    }

    // The run itself succeeds; the write the script attempted is what gets denied, inside the script
    @Test
    public void test_write_inside_script_is_authorized_separately() throws Exception {
        final var script = "import db from 'db';" + "try { db.save(db.name, '" + TestGlobals.COLL
                + "', { _id: 'x', v: 1 }); return 'wrote'; }" + " catch (e) { return 'denied'; }";
        final var responses = exchange(authenticate(READER), runScript(script));
        final var response = responses.get(1);
        assertTrue(response.contains("\"status\":\"OK\""), response);
        assertTrue(response.contains("\"result\":\"denied\""), response);
    }

    @Test
    public void test_run_script_is_rejected_while_a_transaction_is_open() throws Exception {
        final var responses = exchange(authenticate(ADMIN), "{\"type\":\"START_TRANSACTION\"}", runScript("return 1;"));
        assertTrue(responses.get(2).contains("\"errorCode\":\"409-6\""), responses.get(2));
    }

    // A multi-line source with escaped quotes and a template literal survives the line protocol
    @Test
    public void test_multiline_script_round_trips() throws Exception {
        final var script = "const name = \\\"world\\\";\\nconst greeting = `hello ${name}`;\\nreturn greeting;";
        final var responses = exchange(authenticate(ADMIN), runScript(script));
        assertTrue(responses.get(1).contains("\"result\":\"hello world\""), responses.get(1));
    }

    @Test
    public void test_script_error_returns_error_code_and_logs() throws Exception {
        final var responses = exchange(authenticate(ADMIN),
                runScript("console.log('before'); throw new TypeError('boom');"));
        final var response = responses.get(1);
        assertTrue(response.contains("\"errorCode\":\"400-9\""), response);
        assertTrue(response.contains("TypeError: boom"), response);
        assertTrue(response.contains("\"logs\":[\"before\"]"), response);
    }

    @Test
    public void test_disabled_scripts_are_refused_over_the_wire() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptsEnabled", false);
        final var responses = exchange(authenticate(ADMIN), runScript("return 1;"));
        assertTrue(responses.get(1).contains("\"errorCode\":\"403-2\""), responses.get(1));
    }
}
