package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
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
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws InterruptedException, IOException, NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // bulkUpdateEntryCount on CREATED event records a new AdminPageEntry with byte size
    @Test
    public void test_bulk_update_entry_count_created_inserts_admin_page_entry() throws Exception {
        Cache cache = IocContainer.get(Cache.class);

        org.techhouse.ejson.elements.JsonObject d = new org.techhouse.ejson.elements.JsonObject();
        d.addProperty("foo", "bar");
        org.techhouse.data.DbEntry entry = org.techhouse.data.DbEntry.fromJsonObject(
                org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL, d);
        entry.set_id("entryA");
        entry.setPage(0L);

        AdminOperationHelper.bulkUpdateEntryCount(
                org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL,
                org.techhouse.bckg_ops.events.EventType.CREATED, java.util.List.of(entry));

        final var pageEntries = cache.getAdminPageEntries(
                org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL);
        assertNotNull(pageEntries);
        final var pageZero = pageEntries.stream().filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(pageZero.isPresent());
        assertEquals(1, pageZero.get().getEntryCount());
        assertTrue(pageZero.get().getPageSize() > 0);
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

}