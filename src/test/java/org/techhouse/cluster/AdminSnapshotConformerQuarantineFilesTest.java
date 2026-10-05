package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminSnapshotConformerQuarantineFilesTest {
    private static final String DB = "leftdb";
    private static final String COLL = "leftcoll";
    private static final long INCARNATION = 100L;
    private final AdminSnapshotConformer conformer = new AdminSnapshotConformer();
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private Thread holder;
    private CountDownLatch release;

    private boolean conform(AdminSnapshotPayload snapshot) throws Exception {
        return conformer.conform(snapshot, adminEpoch.current());
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        final var config = mock(ClusterConfig.class);
        when(config.replicationAckTimeoutMs()).thenReturn(150L);
        TestUtils.setPrivateField(conformer, "clusterConfig", config);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (release != null) {
            release.countDown();
            holder.join(5000);
        }
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminSnapshotPayload snapshot(List<JsonObject> dbs, List<JsonObject> colls) {
        return new AdminSnapshotPayload(5L, dbs, colls, List.of(), new JsonObject());
    }

    private static JsonObject dbJson() {
        return new AdminDbEntry(DB, new ArrayList<>(), new ArrayList<>()).getData();
    }

    private static JsonObject collJson(long incarnation) {
        final var entry = new AdminCollEntry(DB, COLL, new HashSet<>());
        entry.setIncarnation(incarnation);
        final var json = entry.getData().deepCopy();
        json.addProperty(Globals.PK_FIELD, Cache.getCollectionIdentifier(DB, COLL));
        return json;
    }

    private static File dbFolder() {
        return new File(TestGlobals.PATH + File.separator + DB);
    }

    private static File[] quarantinedIn(File parent, String prefix) {
        final var found = parent.listFiles((_, name) -> name.startsWith(prefix + Globals.QUARANTINE_INFIX));
        assertNotNull(found);
        return found;
    }

    private void installPopulatedCollection() throws Exception {
        conform(snapshot(List.of(dbJson()), List.of(collJson(INCARNATION))));
        final var save = new SaveRequest(DB, COLL);
        final var doc = new JsonObject();
        doc.add(Globals.PK_FIELD, new JsonString("predrop"));
        save.setObject(doc);
        save.set_id("predrop");
        IocContainer.get(OperationProcessor.class).processMessage(save);
        assertFalse(cache.getPkIndexAndLoadIfNecessary(DB, COLL).isEmpty());
    }

    private void writeProcedureFile() throws Exception {
        fs.writeProcedure(DB, "oldproc", "{\"name\":\"oldproc\"}");
    }

    private void unregisterTheWayTheOldConformDid() throws Exception {
        cache.evictCollection(DB, COLL);
        AdminOperationHelper.deleteCollectionEntry(DB, COLL);
        AdminOperationHelper.deletePageCollections(DB, COLL);
    }

    @Test
    public void test_conform_moves_aside_a_collection_absent_from_snapshot() throws Exception {
        installPopulatedCollection();

        assertTrue(conform(snapshot(List.of(dbJson()), List.of())));

        assertNull(cache.getAdminCollectionEntry(DB, COLL));
        assertFalse(new File(dbFolder(), COLL).exists(),
                "a folder left under the live name is adopted by the next CREATE_COLLECTION of that name");
        final var moved = quarantinedIn(dbFolder(), COLL);
        assertEquals(1, moved.length);
        assertTrue(new File(moved[0], COLL + "-pk.idx").length() > 0, "the documents are moved, never deleted");
    }

    @Test
    public void test_conform_moves_aside_a_database_absent_from_snapshot() throws Exception {
        installPopulatedCollection();
        writeProcedureFile();

        assertTrue(conform(snapshot(List.of(), List.of())));

        assertNull(cache.getAdminDbEntry(DB));
        assertFalse(dbFolder().exists());
        final var moved = quarantinedIn(new File(TestGlobals.PATH), DB);
        assertEquals(1, moved.length);
        assertTrue(new File(moved[0], COLL).isDirectory());
        assertTrue(new File(moved[0], Globals.PROCEDURES_FOLDER).isDirectory());
    }

    @Test
    public void test_conform_does_not_adopt_an_unregistered_leftover_collection() throws Exception {
        installPopulatedCollection();
        unregisterTheWayTheOldConformDid();

        assertTrue(conform(snapshot(List.of(dbJson()), List.of(collJson(200L)))));

        assertEquals(200L, cache.getAdminCollectionEntry(DB, COLL).getIncarnation());
        assertTrue(cache.getPkIndexAndLoadIfNecessary(DB, COLL).isEmpty(),
                "the new incarnation must start empty, or anti-entropy spreads the dropped documents");
        assertEquals(1, quarantinedIn(dbFolder(), COLL).length);
    }

    @Test
    public void test_conform_does_not_adopt_an_unregistered_leftover_database() throws Exception {
        installPopulatedCollection();
        writeProcedureFile();
        cache.evictDatabase(DB);
        AdminOperationHelper.deleteDatabaseEntry(DB);

        assertTrue(conform(snapshot(List.of(dbJson()), List.of(collJson(200L)))));

        assertNotNull(cache.getAdminDbEntry(DB));
        assertTrue(fs.listProcedureNames(DB).isEmpty(), "a dropped database's procedures must not come back");
        assertTrue(cache.getPkIndexAndLoadIfNecessary(DB, COLL).isEmpty());
        assertEquals(1, quarantinedIn(new File(TestGlobals.PATH), DB).length);
    }

    @Test
    public void test_conform_adopts_an_empty_unregistered_page_zero() throws Exception {
        fs.createDatabaseFolder(DB);
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(DB, new ArrayList<>(), List.of()));
        fs.createCollectionFile(DB, COLL);

        assertTrue(conform(snapshot(List.of(dbJson()), List.of(collJson(200L)))));

        assertNotNull(cache.getAdminCollectionEntry(DB, COLL));
        assertEquals(0, quarantinedIn(dbFolder(), COLL).length);
    }

    @Test
    public void test_database_install_is_skipped_while_its_barrier_is_held() throws Exception {
        installPopulatedCollection();
        cache.evictDatabase(DB);
        AdminOperationHelper.deleteDatabaseEntry(DB);
        holdTheDatabaseExclusivelyElsewhere();

        assertFalse(conform(snapshot(List.of(dbJson()), List.of(collJson(200L)))));

        assertNull(cache.getAdminDbEntry(DB));
        assertTrue(new File(dbFolder(), COLL).isDirectory(), "nothing is moved without the barrier");
        assertEquals(0, quarantinedIn(new File(TestGlobals.PATH), DB).length);
    }

    private void holdTheDatabaseExclusivelyElsewhere() throws Exception {
        final var taken = new CountDownLatch(1);
        release = new CountDownLatch(1);
        holder = new Thread(() -> {
            try {
                assertTrue(locks.tryLockDatabaseExclusive(DB, 1000));
                taken.countDown();
                release.await();
                locks.releaseDatabaseExclusive(DB);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "database-barrier-holder");
        holder.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_a_leftover_that_cannot_be_moved_is_not_adopted() throws Exception {
        installPopulatedCollection();
        unregisterTheWayTheOldConformDid();
        final var dbDir = dbFolder();
        assertTrue(dbDir.setWritable(false));
        try {
            assertFalse(conform(snapshot(List.of(dbJson()), List.of(collJson(200L)))));
            assertNull(cache.getAdminCollectionEntry(DB, COLL));
        } finally {
            assertTrue(dbDir.setWritable(true));
        }
        assertEquals(0, quarantinedIn(dbDir, COLL).length);
    }
}
