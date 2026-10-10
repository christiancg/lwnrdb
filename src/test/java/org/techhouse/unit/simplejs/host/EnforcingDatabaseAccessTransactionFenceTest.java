package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.tx.TransactionRecovery;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EnforcingDatabaseAccessTransactionFenceTest {
    private static final String ADMIN = "txfenceadmin";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var request = new CreateUserRequest();
        request.setUsername(ADMIN);
        request.setPassword("password123");
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private static UUID sessionClientIdOf(EnforcingDatabaseAccess database)
            throws NoSuchFieldException, IllegalAccessException {
        return TestUtils.getPrivateField(database, "sessionClientId", UUID.class);
    }

    private void assertKeptFencedAndCleanUp(UUID sessionClientId) throws Exception {
        final var transaction = clientTracker.getActiveTransaction(sessionClientId);
        assertNotNull(transaction, "the failed commit must not erase the Client that names the held locks");
        assertFalse(transaction.getHeldLocks().isEmpty(), "the collection stays fenced until recovery finishes");
        assertTrue(org.techhouse.ops.tx.SliceStates.isFenced(transaction.getTransactionId().toString()));

        AdminOperationHelper.deleteTransactionOps(List.copyOf(transaction.getBufferedOpIds()));
        TestUtils.releaseAllLocks();
        clientTracker.clearActiveTransaction(sessionClientId);
        clientTracker.clearTransactionState(sessionClientId);
        clientTracker.removeById(sessionClientId);
    }

    @Test
    public void test_a_throwable_past_the_commit_point_keeps_the_transaction_registered() throws Exception {
        final var database = new EnforcingDatabaseAccess(ADMIN, null);
        database.beginTransaction();
        final var sessionClientId = sessionClientIdOf(database);
        database.save(TestGlobals.DB, TestGlobals.COLL, document("fence-a"));

        try (var recovery = mockStatic(TransactionRecovery.class)) {
            recovery.when(() -> TransactionRecovery.applyAllWithRetry(anyList(), anyString(), any()))
                    .thenThrow(new OutOfMemoryError("simulated"));
            assertThrows(OutOfMemoryError.class, database::commitTransaction);
        }

        assertKeptFencedAndCleanUp(sessionClientId);
    }

    @Test
    public void test_an_exception_past_the_commit_point_is_still_correctly_fenced() throws Exception {
        final var database = new EnforcingDatabaseAccess(ADMIN, null);
        database.beginTransaction();
        final var sessionClientId = sessionClientIdOf(database);
        database.save(TestGlobals.DB, TestGlobals.COLL, document("fence-b"));

        try (var recovery = mockStatic(TransactionRecovery.class)) {
            recovery.when(() -> TransactionRecovery.applyAllWithRetry(anyList(), anyString(), any()))
                    .thenThrow(new RuntimeException("simulated"));
            assertThrows(JsThrowException.class, database::commitTransaction);
        }

        assertKeptFencedAndCleanUp(sessionClientId);
    }
}
