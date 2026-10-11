package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CollectionListingRepairTest {
    private static final String DOCUMENT_ID = "a";
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

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

    private static void killedBetweenRegistrationAndListing() throws Exception {
        AdminOperationHelper.rewriteDatabases(database -> {
            if (!TestGlobals.DB.equals(database.get_id())) {
                return null;
            }
            final var listed = new ArrayList<>(database.getCollections());
            listed.remove(TestGlobals.COLL);
            return new AdminDbEntry(database.get_id(), listed, new ArrayList<>(database.getOwners()));
        });
    }

    private boolean listed() {
        return cache.getAdminDbEntry(TestGlobals.DB).getCollections().contains(TestGlobals.COLL);
    }

    private void saveOneDocument() throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, DOCUMENT_ID);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(DOCUMENT_ID);
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    @Test
    public void test_a_registered_but_unlisted_collection_is_relisted_durably() throws Exception {
        killedBetweenRegistrationAndListing();
        assertFalse(listed());

        AdminOperationHelper.relistUnlistedCollections();

        assertTrue(listed());
        cache.loadAdminData();
        assertTrue(listed(), "the repaired list must reach admin/databases, not only the cache");
    }

    @Test
    public void test_a_database_listing_every_collection_is_not_rewritten() throws Exception {
        final var before = cache.getPkIndexAdminDbEntry(TestGlobals.DB);

        AdminOperationHelper.relistUnlistedCollections();

        assertSame(before, cache.getPkIndexAdminDbEntry(TestGlobals.DB));
    }

    @Test
    public void test_dropping_the_database_unregisters_a_relisted_collection() throws Exception {
        killedBetweenRegistrationAndListing();
        AdminOperationHelper.relistUnlistedCollections();

        AdminOperationHelper.deleteDatabaseEntry(TestGlobals.DB);

        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL),
                "an unlisted collection survived DROP_DATABASE and was inherited by the next database of that name");
    }

    @Test
    public void test_reconcile_all_covers_a_registered_collection_its_database_does_not_list() throws Exception {
        saveOneDocument();
        killedBetweenRegistrationAndListing();
        cache.removeAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);

        PageOccupancyReconciler.reconcileAll();

        final var rows = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(rows);
        assertEquals(fs.pageFileLengths(TestGlobals.DB, TestGlobals.COLL).get(0L),
                rows.stream().filter(row -> row.getPage() == 0L).findFirst().orElseThrow().getPageSize());
    }
}
