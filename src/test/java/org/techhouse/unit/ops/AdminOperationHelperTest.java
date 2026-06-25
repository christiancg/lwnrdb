package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.JsonObject;
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

    // A usage event for a collection that has since been dropped must not recreate a usage row.
    @Test
    public void test_upsert_collection_usage_skips_when_collection_dropped() throws Exception {
        final var droppedDb = "droppedUsageDb";
        final var droppedColl = "droppedUsageColl";
        // Do not register the collection — getCollectionEntry returns null, simulating a dropped state.
        final var mm = IocContainer.get(org.techhouse.cache.MemoryManagement.class);
        mm.recordAccess(org.techhouse.cache.AccessKind.COLLECTION, droppedDb, droppedColl, null);
        final var event = new org.techhouse.bckg_ops.events.CollectionUsageEvent(
                org.techhouse.cache.AccessKind.COLLECTION, droppedDb, droppedColl, null, System.currentTimeMillis());
        AdminOperationHelper.upsertCollectionUsage(event);
        final var id = org.techhouse.data.admin.AdminCollectionUsageEntry.buildId(droppedDb, droppedColl, "");
        Cache cache = IocContainer.get(Cache.class);
        assertNull(cache.getPkIndexCollectionUsage(id), "no usage row must be written for a dropped collection");
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

    // deleteDatabaseEntry must call deletePageCollections for each collection so that
    // admin/pages_<db>_<coll> files and their in-memory entries do not outlive the database.
    @Test
    public void test_delete_database_entry_clears_page_collections() throws Exception {
        final var dbName = "pageLeakDb";
        final var collName = "pageLeakColl";
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(dbName));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(dbName, collName));
        AdminOperationHelper.createPageCollections(dbName, collName);

        // Populate the in-memory page entry so there is something to evict.
        final var cache = IocContainer.get(Cache.class);
        cache.updatePageSizeInMemory(dbName, collName, 0L, 64L);
        assertNotNull(cache.getAdminPageEntries(dbName, collName), "page entries must exist before drop");

        AdminOperationHelper.deleteDatabaseEntry(dbName);

        assertNull(cache.getAdminPageEntries(dbName, collName), "in-memory page entries must be evicted after drop");

        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        final var pagesDir = new File(Configuration.getInstance().getFilePath() + File.separator + Globals.ADMIN_DB_NAME
                + File.separator + pagesCollName);
        assertFalse(pagesDir.exists(), "admin/pages_* directory must be removed after drop");
    }

    // Background CREATED event must not re-apply the delta that updatePageSizeInMemory already applied.
    @Test
    public void test_base_update_entry_count_created_does_not_double_count() throws Exception {
        final var cache = IocContainer.get(Cache.class);

        // Simulate the synchronous call made by OperationProcessor after inserting.
        final long bytes = 64L;
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0L, bytes);

        // Build a DbEntry that mimics what the background EntityEvent carries.
        final var data = new JsonObject();
        data.addProperty("foo", "bar");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("doubleCountId");
        entry.setPage(0L);

        // Background event arrives — must only persist, not bump again.
        AdminOperationHelper.bulkUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                java.util.List.of(entry));

        final var pageEntries = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(pageEntries);
        final var page0 = pageEntries.stream().filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0.isPresent());
        assertEquals(1, page0.get().getEntryCount(), "entryCount must be 1, not 2");
        assertEquals(bytes, page0.get().getPageSize(), "pageSize must equal the single insert, not double");
    }

    // Admin collections call baseUpdateEntryCount directly without a prior updatePageSizeInMemory;
    // multiple inserts onto the same page must each apply their delta normally.
    @Test
    public void test_base_update_entry_count_created_admin_coll_applies_delta() throws Exception {
        // Insert two database entries (second goes onto the same page 0 the first already occupies).
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("adminDeltaDb1"));
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("adminDeltaDb2"));

        final var cache = IocContainer.get(Cache.class);
        final var pageEntries = cache.getAdminPageEntries(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_DATABASES_COLLECTION_NAME);
        assertNotNull(pageEntries);
        final var page0 = pageEntries.stream().filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0.isPresent());
        // There are already admin-created databases (admin itself and the two above), so entryCount >= 2.
        assertTrue(page0.get().getEntryCount() >= 2, "both admin db entries must be counted");
        assertTrue(page0.get().getPageSize() > 0, "pageSize must reflect both entries");
    }

}
