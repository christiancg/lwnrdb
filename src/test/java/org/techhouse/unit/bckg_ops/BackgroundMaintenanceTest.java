package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundLanes;
import org.techhouse.bckg_ops.BackgroundProcessorThread;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.IdleSignal;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.bckg_ops.events.UsageProfileFlushEvent;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRecord;
import org.techhouse.test.TestUtils;

public class BackgroundMaintenanceTest {
    private static final int MAX_BATCH = 256;

    @AfterEach
    public void restoreQueueCap() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "backgroundQueueMaxEvents",
                ConfigKey.BACKGROUND_QUEUE_MAX_EVENTS.defaultValue());
    }

    private static ScriptRunHistoryEvent historyEvent(String runId) {
        return new ScriptRunHistoryEvent(
                new ScriptRunRecord(runId, ScriptRunKind.CALL_PROCEDURE, "laneDb", "job", "proc", null, null, "u1",
                        "u1", 1L, 1L, 1, ScriptRunRecord.OUTCOME_OK, null, null, null, null, null, false));
    }

    private static Object invoke(BackgroundLanes lanes, String name, Class<?>[] types, Object... args)
            throws Exception {
        final Method method = BackgroundLanes.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(lanes, args);
    }

    @Test
    public void test_a_maintenance_event_rides_its_own_queue() throws Exception {
        final var lanes = new BackgroundLanes(2);
        final var flush = new UsageProfileFlushEvent();

        lanes.addMaintenance(flush);

        assertFalse(lanes.isEmpty(), "a queued maintenance event keeps the lanes from reporting empty");
        assertEquals(1, lanes.maintenanceOpen());
        assertNull(invoke(lanes, "poll", new Class<?>[]{int.class}, 0), "it never reaches a hash lane");
        assertSame(flush, invoke(lanes, "pollMaintenance", new Class<?>[0]));
        assertTrue(lanes.isEmpty());
        assertEquals(1, lanes.maintenanceOpen(), "it stays open while it runs");

        invoke(lanes, "maintenanceDone", new Class<?>[0]);

        assertEquals(0, lanes.maintenanceOpen());
    }

    @Test
    public void test_discarding_drops_maintenance_but_counts_only_regular_events() {
        final var lanes = new BackgroundLanes(2);
        lanes.add(new EntityEvent(EventType.CREATED, "laneDb", "coll", new DbEntry()));
        lanes.addMaintenance(new UsageProfileFlushEvent());
        lanes.addMaintenance(new UsageProfileCleanupEvent());

        assertEquals(1, lanes.discardAll());
        assertEquals(0, lanes.maintenanceOpen());
        assertTrue(lanes.isEmpty());
    }

    @Test
    public void test_history_events_are_counted_until_they_leave_a_queue() throws Exception {
        final var lanes = new BackgroundLanes(1);
        for (var i = 0; i < 5; i++) {
            lanes.add(historyEvent("run" + i));
        }
        lanes.add(new EntityEvent(EventType.CREATED, "laneDb", "coll", new DbEntry()));
        assertEquals(5, lanes.historyQueued());

        invoke(lanes, "poll", new Class<?>[]{int.class}, 0);
        assertEquals(4, lanes.historyQueued());

        final var batch = new ArrayList<Event>();
        invoke(lanes, "fill", new Class<?>[]{int.class, List.class, int.class}, 0, batch, 2);
        assertEquals(2, lanes.historyQueued());

        lanes.discardAll();
        assertEquals(0, lanes.historyQueued());
    }

    @Test
    public void test_a_stolen_history_event_leaves_the_count() throws Exception {
        final var lanes = new BackgroundLanes(2);
        final var event = historyEvent("stolen");
        lanes.add(event);
        final var ownLane = (lanes.laneOf(event) + 1) % 2;
        Thread.sleep(80);

        final var batch = new ArrayList<Event>();
        final var stolen = invoke(lanes, "stealFor", new Class<?>[]{int.class, List.class, int.class}, ownLane, batch,
                MAX_BATCH);

        assertSame(event, stolen);
        assertEquals(0, lanes.historyQueued());
    }

    @Test
    public void test_a_worker_settles_a_maintenance_event_even_when_it_throws() throws Exception {
        final var lanes = new BackgroundLanes(1);
        lanes.addMaintenance(new Event(EventType.CREATED) {
        });
        final var inFlight = new AtomicInteger(1);
        lanes.add(new Event(EventType.CREATED) {
        });
        final var idleSignal = new IdleSignal();
        final var thread = new Thread(new BackgroundProcessorThread(lanes, 0, inFlight, new AtomicInteger(),
                new AtomicInteger(1), idleSignal));
        thread.start();
        try {
            assertTrue(idleSignal.awaitIdle(() -> lanes.maintenanceOpen() == 0 && inFlight.get() == 0, 5000L));
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
    }

    @Test
    public void test_maintenance_is_refused_while_draining_and_never_counts_as_pending() {
        final var manager = new BackgroundTaskManager();

        assertTrue(manager.submitMaintenance(new UsageProfileFlushEvent()));
        assertEquals(0, manager.pending());
        assertEquals(1, manager.maintenancePending());

        assertFalse(manager.drain(200L), "a queued maintenance event with no worker is abandoned, not drained");
        assertFalse(manager.submitMaintenance(new UsageProfileFlushEvent()));
        assertEquals(0, manager.maintenancePending());
    }

    @Test
    public void test_a_drain_waits_for_a_maintenance_event_to_finish() {
        final var manager = new BackgroundTaskManager();
        manager.startBackgroundWorkers();
        manager.submitMaintenance(new Event(EventType.CREATED) {
        });

        assertTrue(manager.drain(10_000L));
        assertEquals(0, manager.maintenancePending());
    }

    @Test
    public void test_the_backlog_gate_ignores_queued_history_and_maintenance() throws Exception {
        final var manager = new BackgroundTaskManager();
        TestUtils.setPrivateField(Configuration.getInstance(), "backgroundQueueMaxEvents", 3);
        for (var i = 0; i < 10; i++) {
            manager.submitBackgroundTask(historyEvent("gate" + i));
        }
        manager.submitMaintenance(new UsageProfileFlushEvent());

        assertEquals(10, manager.queuedHistory());
        assertEquals(0, manager.gatedPending());
        assertTrue(manager.awaitCapacity(0L));

        for (var i = 0; i < 3; i++) {
            manager.submitBackgroundTask(new EntityEvent(EventType.CREATED, "gateDb", "c" + i, new DbEntry()));
        }

        assertFalse(manager.awaitCapacity(0L));
        manager.stopBackgroundWorkers();
        assertEquals(0, manager.queuedHistory());
    }
}
