package org.techhouse.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class DropLockEvictionTest {
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Map<String, ReentrantReadWriteLock> lockMap() throws NoSuchFieldException, IllegalAccessException {
        return TestUtils.getPrivateField(locks, "locks", new ReflectionUtils.TypeToken<>() {
        });
    }

    private void touch(String dbName, String collName) throws InterruptedException {
        locks.lock(dbName, collName);
        locks.release(dbName, collName);
    }

    @Test
    public void test_drop_evicts_admin_pages_and_collection_names_locks() throws Exception {
        final var pagesKey = Cache.getCollectionIdentifier(Globals.ADMIN_PAGES_DB_NAME,
                AdminPageEntry.pagesCollectionName(TestGlobals.DB, TestGlobals.COLL));
        final var namesKey = Cache.getCollectionIdentifier(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK);
        touch(Globals.ADMIN_PAGES_DB_NAME, AdminPageEntry.pagesCollectionName(TestGlobals.DB, TestGlobals.COLL));
        touch(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK);
        assertTrue(lockMap().containsKey(pagesKey));

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());

        assertFalse(lockMap().containsKey(pagesKey), "the dropped collection's admin_pages lock is evicted");
        assertFalse(lockMap().containsKey(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        assertTrue(lockMap().containsKey(namesKey), "the database still has collections to name");

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus());

        assertFalse(lockMap().containsKey(namesKey), "the dropped database's collection-names lock is evicted");
        assertFalse(lockMap().containsKey(TestGlobals.DB));
    }

    @Test
    public void test_drop_forgets_the_admin_pages_identifier() {
        final var pagesColl = AdminPageEntry.pagesCollectionName(TestGlobals.DB, TestGlobals.COLL);
        final var remembered = Cache.canonicalNames(Globals.ADMIN_PAGES_DB_NAME, pagesColl);

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());

        assertNotSame(remembered, Cache.canonicalNames(Globals.ADMIN_PAGES_DB_NAME, pagesColl),
                "removing the page-row lock looks the identifier up again, so the drop forgets it afterwards");
    }
}
