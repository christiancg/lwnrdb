package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.*;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorOwnershipTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private static final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_create_database_sets_authenticated_user_as_owner() throws Exception {
        // Create a user and authenticate them
        final var createReq = new CreateUserRequest();
        createReq.setUsername("dbcreator");
        createReq.setPassword("password123");
        createReq.setAdmin(false);
        createReq.setGlobalPermissions(new java.util.HashSet<>());
        createReq.setDatabasePermissions(new java.util.HashMap<>());
        createReq.setCollectionPermissions(new java.util.HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        // Simulate authenticated clientId
        final var clientTracker = IocContainer.get(org.techhouse.conn.ClientTracker.class);
        final var fakeSocket = org.mockito.Mockito.mock(java.net.Socket.class);
        final var fakeAddr = org.mockito.Mockito.mock(java.net.InetAddress.class);
        org.mockito.Mockito.when(fakeSocket.getInetAddress()).thenReturn(fakeAddr);
        org.mockito.Mockito.when(fakeAddr.getHostAddress()).thenReturn("127.0.0.1");
        TestUtils.setPrivateField(org.techhouse.config.Configuration.getInstance(), "maxConnections", 100);
        final var clientId = clientTracker.addClient(fakeSocket);
        clientTracker.setAuthenticatedUser(clientId, "dbcreator");

        final var req = new CreateDatabaseRequest("ownertest-db");
        final var resp = processor.processMessage(req, clientId);
        assertEquals(OperationStatus.OK, resp.getStatus());

        final var dbEntry = cache.getAdminDbEntry("ownertest-db");
        assertNotNull(dbEntry);
        assertTrue(dbEntry.isOwner("dbcreator"), "Creator should be set as owner");

        // Clean up
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_set_database_owners_success() throws Exception {
        TestUtils.createTestDatabaseAndCollection();

        final var req = new SetDatabaseOwnersRequest(TestGlobals.DB);
        req.setOwners(List.of("alice"));

        final var resp = processor.processMessage(req, null);
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertTrue(cache.getAdminDbEntry(TestGlobals.DB).isOwner("alice"));
    }

    @Test
    public void test_set_database_owners_not_found() {
        final var req = new SetDatabaseOwnersRequest("nonexistentdb");
        final var resp = processor.processMessage(req, null);
        assertEquals(OperationStatus.NOT_FOUND, resp.getStatus());
    }
}
