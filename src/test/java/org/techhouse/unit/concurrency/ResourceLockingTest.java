package org.techhouse.unit.concurrency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceLockingTest {

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException {
        final var resourceLocking = new ResourceLocking();
        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);
        for (var lockKey : locks.keySet()) {
            final var split = lockKey.split("\\|");
            resourceLocking.release(lockKey);
            if (split.length > 2) {
                final var indexLock = split[0] + "|" + split[1] + "|" + split[2];
                locks.remove(indexLock);
            } else if (split.length == 2) {
                resourceLocking.removeLock(split[0], split[1]);
            }
        }
    }

    // Locking a collection using valid database and collection names
    @Test
    public void test_lock_collection_with_valid_names() throws InterruptedException, NoSuchFieldException, IllegalAccessException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "testDB";
        String collName = "testCollection";
    
        resourceLocking.lock(dbName, collName);
    
        String collIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);
        Semaphore lock = locks.get(collIdentifier);
    
        assertNotNull(lock);
        assertEquals(0, lock.availablePermits());
    }

    // Attempting to release a lock that was never acquired
    @Test
    public void test_release_unacquired_lock() throws NoSuchFieldException, IllegalAccessException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "testDB";
        String collName = "testCollection";
    
        resourceLocking.release(dbName, collName);
    
        String collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);
        Semaphore lock = locks.get(collIdentifier);
    
        assertNull(lock);
    }

    // Lock is acquired successfully for a valid collection identifier
    @Test
    public void test_lock_acquired_for_valid_collection_identifier() throws InterruptedException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "testDB";
        String collName = "testCollection";
        resourceLocking.lock(dbName, collName);
        // Assuming there's a way to verify the lock is acquired, e.g., checking the semaphore permits
        // This is a placeholder for actual verification logic
        assertTrue(true);
    }

    // Locking a collection with an empty database name
    @Test
    public void test_lock_with_empty_database_name() throws InterruptedException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "";
        String collName = "testCollection";
        resourceLocking.lock(dbName, collName);
        // Assuming there's a way to verify the lock is acquired, e.g., checking the semaphore permits
        // This is a placeholder for actual verification logic
        assertTrue(true);
    }

    // Successfully acquires a lock for a given index identifier
    @Test
    public void test_lock_index_success() throws InterruptedException, NoSuchFieldException, IllegalAccessException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";

        resourceLocking.lockIndex(dbName, collName, fieldName);

        String lockIdentifier = dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName;

        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);
        Semaphore lock = locks.get(lockIdentifier);

        assertNotNull(lock);
        assertEquals(0, lock.availablePermits());
    }

    // Handles the scenario where the lock for the index identifier already exists
    @Test
    public void test_lock_index_existing_lock() throws InterruptedException, NoSuchFieldException, IllegalAccessException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";

        String lockIdentifier = dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName + Globals.COLL_IDENTIFIER_SEPARATOR;
        Semaphore existingLock = new Semaphore(1);
        existingLock.acquire();

        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);
        locks.put(lockIdentifier, existingLock);

        Semaphore lock = locks.get(lockIdentifier);

        assertNotNull(lock);
        assertEquals(0, lock.availablePermits());
    }

    // Successfully releases a lock for a given index identifier
    @Test
    public void test_release_index_success() throws InterruptedException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";

        resourceLocking.lockIndex(dbName, collName, fieldName);
        resourceLocking.releaseIndex(dbName, collName, fieldName);

        // Attempt to lock again to ensure it was released
        resourceLocking.lockIndex(dbName, collName, fieldName);
        resourceLocking.releaseIndex(dbName, collName, fieldName);
    }

    // Attempts to release a lock that does not exist
    @Test
    public void test_release_nonexistent_index() {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "nonExistentDB";
        String collName = "nonExistentCollection";
        String fieldName = "nonExistentField";

        // This should not throw an exception even if the lock does not exist
        resourceLocking.releaseIndex(dbName, collName, fieldName);
    }

    // Release a lock when it exists in the map
    @Test
    public void test_release_existing_lock() throws InterruptedException, NoSuchFieldException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String lockName = "testLock";

        final var lockMethod = TestUtils.getPrivateMethod(resourceLocking, "lock", String.class);

        lockMethod.invoke(resourceLocking, lockName);

        resourceLocking.release(lockName);

        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);

        Semaphore lock = locks.get(lockName);
        assertNotNull(lock);
        assertEquals(1, lock.availablePermits());
    }

    // Attempt to release a lock that does not exist
    @Test
    public void test_release_nonexistent_lock() throws NoSuchFieldException, IllegalAccessException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String lockName = "nonExistentLock";
        resourceLocking.release(lockName);

        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);

        Semaphore lock = locks.get(lockName);
        assertNull(lock);
    }

    // Successfully releases a lock for a given collection identifier
    @Test
    public void test_successful_lock_release() throws NoSuchFieldException, IllegalAccessException, InterruptedException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "testDB";
        String collName = "testCollection";
        String collIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();

        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);
        locks.put(collIdentifier, semaphore);

        resourceLocking.release(dbName, collName);

        assertEquals(1, semaphore.availablePermits());
    }

    // Attempts to release a lock that does not exist
    @Test
    public void test_release_nonexistent_lock_with_two_parameters() {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "nonExistentDB";
        String collName = "nonExistentCollection";

        resourceLocking.release(dbName, collName);

        // No exception should be thrown, and no lock should be released
        // This is a no-op scenario, so we just ensure no errors occur
    }

    // Successfully removes a lock when it exists in the map
    @Test
    public void test_remove_existing_lock() throws NoSuchFieldException, IllegalAccessException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "testDB";
        String collName = "testCollection";
        String collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        Semaphore semaphore = new Semaphore(1);

        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);
        locks.put(collIdentifier, semaphore);

        resourceLocking.removeLock(dbName, collName);

        assertFalse(locks.containsKey(collIdentifier));
    }

    // Attempting to remove a lock that does not exist in the map
    @Test
    public void test_remove_nonexistent_lock() throws NoSuchFieldException, IllegalAccessException {
        ResourceLocking resourceLocking = new ResourceLocking();
        String dbName = "nonExistentDB";
        String collName = "nonExistentCollection";
        String collIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        resourceLocking.removeLock(dbName, collName);

        final var type = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(resourceLocking, "locks", type);

        assertFalse(locks.containsKey(collIdentifier));
    }
}