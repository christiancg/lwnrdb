package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundLanes;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionScopedEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.UsageProfileCleanupEvent;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;

public class BackgroundLanesTest {
    private static final int LANES = 8;
    private static final int MAX_BATCH = 256;

    private static EntityEvent eventOn(String collName) {
        return new EntityEvent(EventType.CREATED, "laneDb", collName, new DbEntry());
    }

    private static String collectionOnLane(BackgroundLanes lanes, int lane) {
        for (var i = 0;; i++) {
            final var candidate = "coll" + i;
            if (lanes.laneOf(eventOn(candidate)) == lane) {
                return candidate;
            }
        }
    }

    private static Event stealForLaneZero(BackgroundLanes lanes, List<Event> batch) throws Exception {
        final Method steal = BackgroundLanes.class.getDeclaredMethod("stealFor", int.class, List.class, int.class);
        steal.setAccessible(true);
        return (Event) steal.invoke(lanes, 0, batch, MAX_BATCH);
    }

    private static Event poll(BackgroundLanes lanes, int lane) throws Exception {
        final Method poll = BackgroundLanes.class.getDeclaredMethod("poll", int.class);
        poll.setAccessible(true);
        return (Event) poll.invoke(lanes, lane);
    }

    @Test
    public void test_one_collection_routes_to_one_lane() {
        final var lanes = new BackgroundLanes(LANES);
        final var lane = lanes.laneOf(eventOn("orders"));

        assertEquals(lane, lanes.laneOf(new EntityEvent(EventType.DELETED, "laneDb", "orders", new DbEntry())));
        assertEquals(lane, lanes.laneOf(new BulkEntityEvent("laneDb", "orders", List.of(), List.of())));
        assertEquals(0, lanes.laneOf(new UsageProfileCleanupEvent()));
        assertTrue(lane >= 0 && lane < LANES);
    }

    @Test
    public void test_collections_spread_over_the_lanes() {
        final var lanes = new BackgroundLanes(LANES);
        final var used = new java.util.HashSet<Integer>();
        for (var i = 0; i < 200; i++) {
            used.add(lanes.laneOf(eventOn("spread" + i)));
        }

        assertEquals(LANES, used.size());
    }

    @Test
    public void test_a_lane_count_below_one_is_one_lane() {
        final var lanes = new BackgroundLanes(0);

        assertEquals(1, lanes.count());
        assertEquals(0, lanes.laneOf(eventOn("any")));
    }

    @Test
    public void test_an_event_in_any_lane_makes_the_lanes_non_empty() {
        final var lanes = new BackgroundLanes(LANES);
        assertTrue(lanes.isEmpty());

        lanes.add(eventOn(collectionOnLane(lanes, LANES - 1)));

        assertFalse(lanes.isEmpty());
        assertEquals(1, lanes.discardAll());
        assertTrue(lanes.isEmpty());
    }

    @Test
    public void test_an_unserved_lane_is_stolen_after_the_threshold() throws Exception {
        final var lanes = new BackgroundLanes(2);
        final var coll = collectionOnLane(lanes, 1);
        final var first = eventOn(coll);
        lanes.addAll(List.of(first, eventOn(coll), eventOn(coll)));
        Thread.sleep(Globals.BACKGROUND_STEAL_AFTER_MS + 20);

        final var batch = new ArrayList<Event>();
        assertSame(first, stealForLaneZero(lanes, batch));

        assertEquals(3, batch.size(), "a steal takes the whole batch from that lane");
        assertTrue(lanes.isEmpty());
    }

    @Test
    public void test_a_recently_served_lane_is_not_stolen() throws Exception {
        final var lanes = new BackgroundLanes(2);
        final var coll = collectionOnLane(lanes, 1);
        Thread.sleep(Globals.BACKGROUND_STEAL_AFTER_MS + 20);
        assertNull(poll(lanes, 1));
        lanes.add(eventOn(coll));

        assertNull(stealForLaneZero(lanes, new ArrayList<>()));
        assertFalse(lanes.isEmpty());
    }

    @Test
    public void test_a_worker_never_steals_its_own_lane() throws Exception {
        final var lanes = new BackgroundLanes(2);
        lanes.add(eventOn(collectionOnLane(lanes, 0)));
        Thread.sleep(Globals.BACKGROUND_STEAL_AFTER_MS + 20);

        assertNull(stealForLaneZero(lanes, new ArrayList<>()));
        assertNotEquals(null, poll(lanes, 0));
    }

    private static class Recorded extends CollectionScopedEvent {
        protected final List<String> seen;
        private final String label;

        private Recorded(String collName, List<String> seen, String label) {
            super(EventType.CREATED, "laneDb", collName);
            this.seen = seen;
            this.label = label;
        }

        @Override
        public String toString() {
            seen.add(label);
            return label;
        }
    }

    private static final class Stuck extends Recorded {
        private final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release;

        private Stuck(String collName, List<String> seen, java.util.concurrent.CountDownLatch release) {
            super(collName, seen, "stuck");
            this.release = release;
        }

        @Override
        public String toString() {
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    seen.add("never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.toString();
        }
    }

    @Test
    public void test_a_worker_serves_a_stale_foreign_lane_before_its_own() throws Exception {
        final var lanes = new BackgroundLanes(2);
        final var seen = java.util.Collections.synchronizedList(new ArrayList<String>());
        lanes.add(new Recorded(collectionOnLane(lanes, 1), seen, "stale"));
        Thread.sleep(Globals.BACKGROUND_STEAL_AFTER_MS + 20);
        lanes.add(new Recorded(collectionOnLane(lanes, 0), seen, "own"));
        final var inFlight = new java.util.concurrent.atomic.AtomicInteger(2);
        final var idleSignal = new org.techhouse.bckg_ops.IdleSignal();
        final var worker = new Thread(new org.techhouse.bckg_ops.BackgroundProcessorThread(lanes, 0, inFlight,
                new java.util.concurrent.atomic.AtomicInteger(), new java.util.concurrent.atomic.AtomicInteger(1),
                idleSignal));
        worker.start();
        try {
            assertTrue(idleSignal.awaitIdle(() -> inFlight.get() <= 0, 5_000L));
            assertEquals("stale", seen.getFirst(), seen.toString());
            assertTrue(seen.contains("own"), seen.toString());
        } finally {
            worker.interrupt();
            worker.join(5_000);
        }
    }

    @Test
    public void test_a_lane_whose_owner_is_stuck_is_served_by_another_worker() throws Exception {
        final var lanes = new BackgroundLanes(2);
        final var seen = java.util.Collections.synchronizedList(new ArrayList<String>());
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var stuckCollection = collectionOnLane(lanes, 0);
        final var inFlight = new java.util.concurrent.atomic.AtomicInteger(2);
        final var idleSignal = new org.techhouse.bckg_ops.IdleSignal();
        final var workerCount = new java.util.concurrent.atomic.AtomicInteger(2);
        final var parked = new java.util.concurrent.atomic.AtomicInteger();
        final var stuck = new Stuck(stuckCollection, seen, release);
        lanes.add(stuck);
        final var owner = new Thread(new org.techhouse.bckg_ops.BackgroundProcessorThread(lanes, 0, inFlight, parked,
                workerCount, idleSignal));
        owner.start();
        assertTrue(stuck.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the owner took the stuck event");
        lanes.add(new Recorded(collectionOnLane(lanes, 0), seen, "waiting"));
        final var helper = new Thread(new org.techhouse.bckg_ops.BackgroundProcessorThread(lanes, 1, inFlight, parked,
                workerCount, idleSignal));
        helper.start();
        try {
            assertTrue(idleSignal.awaitIdle(() -> seen.contains("waiting"), 3_000L),
                    "another worker serves the stuck owner's lane: " + seen);
            assertFalse(seen.contains("stuck"), "the owner is still stuck when its lane is served");
            release.countDown();
            assertTrue(idleSignal.awaitIdle(() -> inFlight.get() <= 0, 5_000L));
            assertFalse(seen.contains("never released"), seen.toString());
        } finally {
            release.countDown();
            owner.interrupt();
            helper.interrupt();
            owner.join(5_000);
            helper.join(5_000);
        }
    }

    private static void fillUntil(BackgroundLanes lanes, List<Event> batch, int maxBatch, int target,
            long deadlineNanos) throws Exception {
        final Method fill = BackgroundLanes.class.getDeclaredMethod("fillUntil", int.class, List.class, int.class,
                int.class, long.class);
        fill.setAccessible(true);
        fill.invoke(lanes, 0, batch, maxBatch, target, deadlineNanos);
    }

    @Test
    public void test_fill_until_returns_once_the_target_is_reached() throws Exception {
        final var lanes = new BackgroundLanes(1);
        final var batch = new ArrayList<Event>();
        for (var i = 0; i < 3; i++) {
            lanes.add(eventOn("orders"));
        }
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (var i = 0; i < 5; i++) {
                lanes.add(eventOn("orders"));
            }
        });
        final var started = System.nanoTime();

        fillUntil(lanes, batch, MAX_BATCH, 8, started + 5_000_000_000L);

        assertEquals(8, batch.size());
        assertTrue(System.nanoTime() - started < 4_000_000_000L, "it returns as soon as the target is met");
    }

    @Test
    public void test_fill_until_returns_at_the_deadline_with_what_arrived() throws Exception {
        final var lanes = new BackgroundLanes(1);
        final var batch = new ArrayList<Event>();
        lanes.add(eventOn("orders"));
        final var deadline = System.nanoTime() + 30_000_000L;

        fillUntil(lanes, batch, MAX_BATCH, 64, deadline);

        assertEquals(1, batch.size());
        assertTrue(System.nanoTime() >= deadline, "it waits until the deadline for company");
        assertTrue(lanes.isEmpty());
    }

    @Test
    public void test_fill_until_never_exceeds_the_max_batch() throws Exception {
        final var lanes = new BackgroundLanes(1);
        final var batch = new ArrayList<Event>();
        for (var i = 0; i < 20; i++) {
            lanes.add(eventOn("orders"));
        }

        fillUntil(lanes, batch, 10, 64, System.nanoTime() + 1_000_000_000L);

        assertEquals(10, batch.size());
        assertFalse(lanes.isEmpty(), "the rest stays queued for the next batch");
    }

    @Test
    public void test_an_interrupt_during_fill_until_keeps_the_taken_events_and_the_flag() throws Exception {
        final var lanes = new BackgroundLanes(1);
        final var batch = new ArrayList<Event>(List.of(eventOn("orders")));
        lanes.add(eventOn("orders"));
        final var interrupted = new boolean[1];
        final var worker = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            try {
                fillUntil(lanes, batch, MAX_BATCH, 64, System.nanoTime() + 5_000_000_000L);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            interrupted[0] = Thread.currentThread().isInterrupted();
        });
        worker.join(5_000);

        assertEquals(1, batch.size(), "the event taken before the interrupt is kept for processing");
        assertFalse(lanes.isEmpty(), "an event still queued stays queued");
        assertTrue(interrupted[0], "the interrupt flag is restored");
    }
}
