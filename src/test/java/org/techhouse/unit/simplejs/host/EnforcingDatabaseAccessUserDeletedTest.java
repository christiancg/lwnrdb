package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EnforcingDatabaseAccessUserDeletedTest {
    private static final String USER = "vanishinguser";

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var request = new CreateUserRequest();
        request.setUsername(USER);
        request.setPassword("password123");
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private EnforcingDatabaseAccess transactionHoldingTheCollectionLock() throws Exception {
        final var database = new EnforcingDatabaseAccess(USER, null);
        database.beginTransaction();
        database.save(TestGlobals.DB, TestGlobals.COLL, document("first"));
        AdminOperationHelper.deleteUserEntry(USER);
        return database;
    }

    private boolean collectionWriteLockIsFreeForAnotherThread() throws Exception {
        final var acquired = new boolean[1];
        final var probe = new Thread(() -> {
            try {
                acquired[0] = locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL, 200);
                if (acquired[0]) {
                    locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        probe.start();
        probe.join(TimeUnit.SECONDS.toMillis(5));
        return acquired[0];
    }

    @Test
    public void test_a_save_after_the_user_vanished_is_still_refused() throws Exception {
        final var database = transactionHoldingTheCollectionLock();

        assertThrows(JsThrowException.class, () -> database.save(TestGlobals.DB, TestGlobals.COLL, document("second")));
    }

    @Test
    public void test_a_rollback_after_the_user_vanished_releases_the_write_lock() throws Exception {
        final var database = transactionHoldingTheCollectionLock();

        assertDoesNotThrow(database::rollbackTransaction);

        assertFalse(database.hasActiveTransaction());
        assertTrue(collectionWriteLockIsFreeForAnotherThread());
    }

    @Test
    public void test_a_commit_after_the_user_vanished_leaves_no_lock_behind() throws Exception {
        final var database = transactionHoldingTheCollectionLock();

        assertDoesNotThrow(database::commitTransaction);

        assertTrue(collectionWriteLockIsFreeForAnotherThread());
    }
}
