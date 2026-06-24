package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class AdminOperationHelperTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // bulkUpdateEntryCount on CREATED event records a new AdminPageEntry with byte size
    @Test
    public void test_bulk_update_entry_count_created_inserts_admin_page_entry() throws Exception {
        Cache cache = IocContainer.get(Cache.class);

        org.techhouse.ejson.elements.JsonObject d = new org.techhouse.ejson.elements.JsonObject();
        d.addProperty("foo", "bar");
        org.techhouse.data.DbEntry entry = org.techhouse.data.DbEntry.fromJsonObject(org.techhouse.test.TestGlobals.DB,
                org.techhouse.test.TestGlobals.COLL, d);
        entry.set_id("entryA");
        entry.setPage(0L);

        AdminOperationHelper.bulkUpdateEntryCount(org.techhouse.test.TestGlobals.DB,
                org.techhouse.test.TestGlobals.COLL, org.techhouse.bckg_ops.events.EventType.CREATED,
                java.util.List.of(entry));

        final var pageEntries = cache.getAdminPageEntries(org.techhouse.test.TestGlobals.DB,
                org.techhouse.test.TestGlobals.COLL);
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
        final var typeToken = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        final var actualLocks = TestUtils.getPrivateField(locks, "locks", typeToken);
        assertNotNull(actualLocks
                .get(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME)));
        final var inserted = cache.getAdminDbEntry(Globals.ADMIN_DB_NAME);
        assertNotNull(inserted);
    }

    // saveDatabaseEntry for an already-existing database takes the update path (L204-205)
    @Test
    public void test_save_database_entry_updates_existing() {
        // TestGlobals.DB was created in @BeforeEach — calling saveDatabaseEntry again triggers the update path
        AdminDbEntry dbEntry = new AdminDbEntry(TestGlobals.DB);
        // This should update the existing entry rather than insert
        assertDoesNotThrow(() -> AdminOperationHelper.saveDatabaseEntry(dbEntry));
        Cache cache = IocContainer.get(Cache.class);
        assertNotNull(cache.getAdminDbEntry(TestGlobals.DB));
    }

    // saveCollectionEntry for an already-existing collection takes the update path (L269-270)
    @Test
    public void test_save_collection_entry_updates_existing() {
        AdminCollEntry collEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        // TestGlobals.DB/COLL already exists — calling saveCollectionEntry triggers the update path
        assertDoesNotThrow(() -> AdminOperationHelper.saveCollectionEntry(collEntry));
        Cache cache = IocContainer.get(Cache.class);
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    // deleteDatabaseEntry with collections iterates and deletes them (L229-231)
    @Test
    public void test_delete_database_entry_with_collections() throws Exception {
        // Create a new database with a collection, then delete the database
        AdminDbEntry dbEntry = new AdminDbEntry("toDeleteWithColl");
        AdminOperationHelper.saveDatabaseEntry(dbEntry);
        AdminCollEntry collEntry = new AdminCollEntry("toDeleteWithColl", "aColl");
        AdminOperationHelper.saveCollectionEntry(collEntry);

        assertDoesNotThrow(() -> AdminOperationHelper.deleteDatabaseEntry("toDeleteWithColl"));

        Cache cache = IocContainer.get(Cache.class);
        assertNull(cache.getAdminDbEntry("toDeleteWithColl"));
    }

    // deleteCollectionEntry removes a collection (exercises L299-300 path)
    @Test
    public void test_delete_collection_entry_existing() throws Exception {
        AdminCollEntry collEntry = new AdminCollEntry(TestGlobals.DB, "tempColl");
        AdminOperationHelper.saveCollectionEntry(collEntry);

        assertDoesNotThrow(() -> AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, "tempColl"));

        Cache cache = IocContainer.get(Cache.class);
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "tempColl"));
    }

    // saveCollectionEntry also mutates the parent AdminDbEntry in admin/databases, so it now holds the
    // databases lock too; both that lock and the admin/collections lock must be released when it returns.
    @Test
    public void test_save_collection_entry_releases_databases_and_collections_locks() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, "lockProbeColl"));

        assertTrue(locks.tryLockWrite(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME),
                "admin/databases lock must be released after saveCollectionEntry");
        locks.releaseWrite(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        assertTrue(locks.tryLockWrite(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME),
                "admin/collections lock must be released after saveCollectionEntry");
        locks.releaseWrite(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
    }

    // deleteCollectionEntry likewise touches admin/databases under both locks and must release both.
    @Test
    public void test_delete_collection_entry_releases_databases_and_collections_locks() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, "lockProbeColl2"));
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, "lockProbeColl2");

        assertTrue(locks.tryLockWrite(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME),
                "admin/databases lock must be released after deleteCollectionEntry");
        locks.releaseWrite(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        assertTrue(locks.tryLockWrite(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME),
                "admin/collections lock must be released after deleteCollectionEntry");
        locks.releaseWrite(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
    }

    @Test
    public void test_upsert_collection_usage_creates_then_updates() throws Exception {
        final var mm = IocContainer.get(org.techhouse.cache.MemoryManagement.class);
        mm.recordAccess(org.techhouse.cache.AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL, null);
        final var event = new org.techhouse.bckg_ops.events.CollectionUsageEvent(
                org.techhouse.cache.AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL, null,
                System.currentTimeMillis());
        AdminOperationHelper.upsertCollectionUsage(event);
        final var id = org.techhouse.data.admin.AdminCollectionUsageEntry.buildId(TestGlobals.DB, TestGlobals.COLL, "");
        Cache cache = IocContainer.get(Cache.class);
        assertNotNull(cache.getPkIndexCollectionUsage(id));
        // Second time goes through the UPDATE path
        mm.recordAccess(org.techhouse.cache.AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL, null);
        assertDoesNotThrow(() -> AdminOperationHelper.upsertCollectionUsage(event));
    }

    @Test
    public void test_upsert_collection_usage_ignores_admin_db() throws Exception {
        final var event = new org.techhouse.bckg_ops.events.CollectionUsageEvent(
                org.techhouse.cache.AccessKind.COLLECTION, Globals.ADMIN_DB_NAME, "databases", null,
                System.currentTimeMillis());
        AdminOperationHelper.upsertCollectionUsage(event);
        final var id = org.techhouse.data.admin.AdminCollectionUsageEntry.buildId(Globals.ADMIN_DB_NAME, "databases",
                "");
        Cache cache = IocContainer.get(Cache.class);
        assertNull(cache.getPkIndexCollectionUsage(id));
    }

    @Test
    public void test_cleanup_collection_usage_removes_stale_only() throws Exception {
        final var mm = IocContainer.get(org.techhouse.cache.MemoryManagement.class);
        mm.recordAccess(org.techhouse.cache.AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL, null);
        final var event = new org.techhouse.bckg_ops.events.CollectionUsageEvent(
                org.techhouse.cache.AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL, null,
                System.currentTimeMillis());
        AdminOperationHelper.upsertCollectionUsage(event);
        // Cleanup with maxAge=Long.MAX_VALUE — nothing should be removed (everything is recent).
        AdminOperationHelper.cleanupCollectionUsage(Long.MAX_VALUE);
        final var id = org.techhouse.data.admin.AdminCollectionUsageEntry.buildId(TestGlobals.DB, TestGlobals.COLL, "");
        Cache cache = IocContainer.get(Cache.class);
        assertNotNull(cache.getPkIndexCollectionUsage(id));
        // Make sure the threshold strictly exceeds the recorded lastAccessMillis (avoid same-millisecond race).
        Thread.sleep(5);
        // Cleanup with maxAge=0 — everything older than `now` removed (i.e. all entries).
        AdminOperationHelper.cleanupCollectionUsage(0L);
        assertNull(cache.getPkIndexCollectionUsage(id));
    }

}
