package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PageOccupancyReconcilerReplayTest {
    private static final String UNREGISTERED = "notRegistered";
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private BackgroundTaskManager originalTaskManager;
    private BackgroundTaskManager stubTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalTaskManager = TestUtils.getPrivateStaticField(PageOccupancyReconciler.class, "taskManager",
                BackgroundTaskManager.class);
        stubTaskManager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", stubTaskManager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", originalTaskManager);
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        pendingIndexWrites.clearCollection(TestGlobals.DB, UNREGISTERED);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static PkIndexEntry written(String dbName, String collName, String id, long page) {
        return new PkIndexEntry(dbName, collName, id, 0L, 0L, page, 0L);
    }

    @Test
    public void test_replayed_ids_are_marked_and_queued_with_their_generation() throws Exception {
        final var replayed = PageOccupancyReconciler
                .markReplayed(List.of(written(TestGlobals.DB, TestGlobals.COLL, "a", 0L),
                        written(TestGlobals.DB, TestGlobals.COLL, "b", 1L)));

        assertEquals(Set.of("a", "b"), pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL),
                "marked before any other startup step can checkpoint the replayed log");
        assertEquals(1, replayed.size());

        PageOccupancyReconciler.scheduleIndexRepairFor(replayed);

        final var repairs = QueuedRepairs.of(stubTaskManager);
        assertEquals(1, repairs.size(), "one repair pass per collection, not one event per id");
        assertEquals(List.of("a", "b"), repairs.getFirst().getIds());
        assertEquals(replayed.getFirst().generation(), repairs.getFirst().getPendingGeneration());
    }

    @Test
    public void test_admin_collections_are_not_marked() {
        final var replayed = PageOccupancyReconciler.markReplayed(
                List.of(written(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, "x", 0L)));

        assertEquals(List.of(), replayed);
        assertTrue(pendingIndexWrites.idsFor(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME).isEmpty());
    }

    @Test
    public void test_an_unregistered_collection_has_its_marks_cleared() throws Exception {
        final var replayed = PageOccupancyReconciler
                .markReplayed(List.of(written(TestGlobals.DB, UNREGISTERED, "z", 0L)));
        assertEquals(Set.of("z"), pendingIndexWrites.idsFor(TestGlobals.DB, UNREGISTERED));

        PageOccupancyReconciler.scheduleIndexRepairFor(replayed);

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, UNREGISTERED).isEmpty(),
                "marks nobody will drain would keep a checkpoint writing the marker forever");
        assertEquals(List.of(), QueuedRepairs.of(stubTaskManager));
    }
}
