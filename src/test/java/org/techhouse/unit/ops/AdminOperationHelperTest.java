package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;

public class AdminOperationHelperTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws InterruptedException, IOException, NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Database entry creation and update with proper locking and cache management
    @Test
    public void test_save_database_entry_creates_and_updates_with_locking() throws Exception {
        // Arrange
        AdminDbEntry dbEntry = new AdminDbEntry(Globals.ADMIN_DB_NAME);
        ResourceLocking locks = IocContainer.get(ResourceLocking.class);
        Cache cache = IocContainer.get(Cache.class);

        // Act
        AdminOperationHelper.saveDatabaseEntry(dbEntry);
    
        // Assert
        final var typeToken = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var actualLocks = TestUtils.getPrivateField(locks, "locks", typeToken);
        assertNotNull(actualLocks.get(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME)));
        final var inserted = cache.getAdminDbEntry(Globals.ADMIN_DB_NAME);
        assertNotNull(inserted);
    }

    // Successfully updates entry count for existing collection with positive count
    @Test
    public void test_bulk_update_entry_count_positive_value() throws IOException, InterruptedException {
        // Arrange
        String dbName = "testDb";
        String collName = "testColl";
        int insertCount = 5;

        AdminCollEntry existingEntry = new AdminCollEntry(dbName, collName);
        existingEntry.setEntryCount(10);

        Cache cache = IocContainer.get(Cache.class);

        // Act
        AdminOperationHelper.bulkUpdateEntryCount(dbName, collName, insertCount);

        // Assert
        assertEquals(insertCount, cache.getAdminCollectionEntry(dbName, collName).getEntryCount());
    }

    // Handle zero insertedCount value
    @Test
    public void test_bulk_update_entry_count_zero_value() throws IOException, InterruptedException {
        // Arrange
        String dbName = "testDb";
        String collName = "testColl";
        int insertCount = 0;

        AdminCollEntry existingEntry = new AdminCollEntry(dbName, collName);
        existingEntry.setEntryCount(0);

        Cache cache = IocContainer.get(Cache.class);

        // Act
        AdminOperationHelper.bulkUpdateEntryCount(dbName, collName, insertCount);

        // Assert
        assertEquals(insertCount, cache.getAdminCollectionEntry(dbName, collName).getEntryCount());
    }
}