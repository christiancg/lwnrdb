package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cache.UsageTracker;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class UsageFlushTest {
    private static final String DB = "flushDb";
    private static final String COLL = "flushColl";

    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private BackgroundTaskManager sharedManager;
    private BackgroundTaskManager manager;
    private long savedMaxMemoryBytes;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        savedMaxMemoryBytes = Configuration.getInstance().getMaxMemoryBytes();
        TestUtils.resetUsageCounters(TestUtils.usageTrackerOf(memoryManagement));
        sharedManager = TestUtils.getPrivateField(memoryManagement, "taskManager", BackgroundTaskManager.class);
        manager = new BackgroundTaskManager();
        TestUtils.setPrivateField(memoryManagement, "taskManager", manager);
        memoryManagement.takeDirtyUsage();
    }

    @AfterEach
    public void tearDown() throws Exception {
        manager.stopBackgroundWorkers();
        TestUtils.setPrivateField(memoryManagement, "taskManager", sharedManager);
        TestUtils.setPrivateField(Configuration.getInstance(), "maxMemoryBytes", savedMaxMemoryBytes);
        memoryManagement.takeDirtyUsage();
        TestUtils.resetUsageCounters(TestUtils.usageTrackerOf(memoryManagement));
        TestUtils.standardTearDown();
    }

    @Test
    public void test_a_requeued_counter_is_dirty_again_and_drained_once() {
        final var tracker = new UsageTracker();
        tracker.recordAccess(AccessKind.COLLECTION, DB, COLL, null);
        assertTrue(tracker.hasDirtyCounters());
        final var drained = tracker.drainDirtyCounters();
        assertFalse(tracker.hasDirtyCounters());

        tracker.requeue(drained);
        tracker.requeue(drained);

        assertTrue(tracker.hasDirtyCounters());
        final var again = tracker.drainDirtyCounters();
        assertEquals(1, again.size());
        assertSame(drained.getFirst(), again.getFirst());
    }

    @Test
    public void test_ticks_while_a_flush_is_queued_queue_nothing_more() {
        memoryManagement.recordAccess(AccessKind.COLLECTION, DB, COLL, null);

        memoryManagement.flushUsageProfile();
        memoryManagement.recordAccess(AccessKind.COLLECTION, DB, "other", null);
        memoryManagement.flushUsageProfile();

        assertEquals(1, manager.maintenancePending());
        assertEquals(0, manager.pending(), "a usage flush never counts against the backlog gate");
    }

    @Test
    public void test_taking_the_dirty_counters_lets_the_next_tick_queue_a_flush() {
        memoryManagement.recordAccess(AccessKind.COLLECTION, DB, COLL, null);
        memoryManagement.flushUsageProfile();

        final var taken = memoryManagement.takeDirtyUsage();
        assertEquals(1, taken.size());
        memoryManagement.flushUsageProfile();
        assertEquals(1, manager.maintenancePending(), "nothing dirty: the tick queues nothing");

        memoryManagement.requeueUsage(taken);
        memoryManagement.flushUsageProfile();

        assertEquals(2, manager.maintenancePending());
    }

    @Test
    public void test_a_clean_profile_or_disabled_caching_queues_nothing() throws Exception {
        memoryManagement.flushUsageProfile();
        assertEquals(0, manager.maintenancePending());

        memoryManagement.recordAccess(AccessKind.COLLECTION, DB, COLL, null);
        TestUtils.setPrivateField(Configuration.getInstance(), "maxMemoryBytes", -1L);
        memoryManagement.flushUsageProfile();

        assertEquals(0, manager.maintenancePending());
    }

    @Test
    public void test_a_refused_flush_is_queued_again_by_a_later_tick() {
        memoryManagement.recordAccess(AccessKind.COLLECTION, DB, COLL, null);
        manager.drain(0L);

        memoryManagement.flushUsageProfile();
        assertEquals(0, manager.maintenancePending());

        manager.startBackgroundWorkers();
        manager.stopBackgroundWorkers();
        memoryManagement.flushUsageProfile();

        assertEquals(1, manager.maintenancePending());
    }
}
