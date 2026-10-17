package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.test.ResponseLines;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class UserOperationHelperSetPasswordSessionTest {
    private static final String PASSWORD = "password123";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration config = Configuration.getInstance();
    private final List<UUID> clients = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(config, "maxConnections", 0);
        createUser("pat", false);
        createUser("quinn", false);
        createUser("root_admin", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        clients.forEach(clientTracker::removeById);
        TestUtils.setPrivateField(config, "maxConnections", "100");
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void createUser(String username, boolean admin) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword(PASSWORD);
        request.setAdmin(admin);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    private UUID connectAs(String username) {
        final var clientId = clientTracker.addClient(TestUtils.clientSocket());
        clients.add(clientId);
        clientTracker.setAuthenticatedUser(clientId, username);
        return clientId;
    }

    private static SetPasswordRequest change(String username, String current, String next) {
        final var request = new SetPasswordRequest();
        request.setUsername(username);
        request.setCurrentPassword(current);
        request.setNewPassword(next);
        return request;
    }

    @Test
    public void own_change_keeps_the_callers_connection_and_drops_the_others() {
        final var caller = connectAs("pat");
        final var other = connectAs("pat");

        final var response = UserOperationHelper.processSetPassword(change("pat", PASSWORD, "renewed-pass"), caller);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("pat", clientTracker.getAuthenticatedUsername(caller));
        assertNull(clientTracker.getAuthenticatedUsername(other));
    }

    @Test
    public void admin_change_drops_every_connection_of_the_target() {
        final var admin = connectAs("root_admin");
        final var first = connectAs("pat");
        final var second = connectAs("pat");

        final var response = UserOperationHelper.processSetPassword(change("pat", null, "reset-by-admin"), admin);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(clientTracker.getAuthenticatedUsername(first));
        assertNull(clientTracker.getAuthenticatedUsername(second));
        assertEquals("root_admin", clientTracker.getAuthenticatedUsername(admin));
    }

    @Test
    public void failed_change_drops_nobody() {
        final var caller = connectAs("pat");
        final var other = connectAs("pat");

        final var response = UserOperationHelper.processSetPassword(change("pat", "not-the-password", "x-new-pass"),
                caller);

        assertEquals(ErrorCode.CURRENT_PASSWORD_INCORRECT.getCode(), response.getErrorCode());
        assertEquals("pat", clientTracker.getAuthenticatedUsername(caller));
        assertEquals("pat", clientTracker.getAuthenticatedUsername(other));
    }

    @Test
    public void other_users_connections_untouched() {
        final var caller = connectAs("pat");
        final var bystander = connectAs("quinn");

        UserOperationHelper.processSetPassword(change("pat", PASSWORD, "renewed-pass"), caller);

        assertEquals("quinn", clientTracker.getAuthenticatedUsername(bystander));
    }

    @Test
    public void a_deauthenticated_connection_can_still_roll_back_its_transaction() throws Exception {
        createUser("tx_holder", true);
        final var toServer = new PipedOutputStream();
        final var responses = new ResponseLines();
        final var socket = TestUtils.clientSocket(new PipedInputStream(toServer, 1 << 16), responses);
        final var connection = new Thread(new MessageProcessor(socket));
        connection.start();
        try (toServer) {
            final var send = (java.util.function.Function<String, String>) message -> {
                try {
                    toServer.write((message + "\n").getBytes(StandardCharsets.UTF_8));
                    toServer.flush();
                    return responses.next();
                } catch (java.io.IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            };
            assertTrue(send
                    .apply("{\"type\":\"AUTHENTICATE\",\"username\":\"tx_holder\",\"password\":\"" + PASSWORD + "\"}")
                    .contains("\"OK\""));
            assertTrue(send.apply("{\"type\":\"START_TRANSACTION\"}").contains("\"OK\""));
            assertTrue(send.apply("{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                    + TestGlobals.COLL + "\",\"object\":{\"_id\":\"held\"}}").contains("\"OK\""));

            final var admin = connectAs("root_admin");
            assertEquals(OperationStatus.OK, UserOperationHelper
                    .processSetPassword(change("tx_holder", null, "reset-by-admin"), admin).getStatus());

            final var rollback = send.apply("{\"type\":\"ROLLBACK_TRANSACTION\"}");
            assertTrue(rollback.contains("\"OK\""), rollback);
            assertTrue(send.apply("{\"type\":\"LIST_DATABASES\"}").contains("401-1"));
        } finally {
            connection.join(10_000);
        }
    }
}
