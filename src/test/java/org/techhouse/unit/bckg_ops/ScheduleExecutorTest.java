package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleExecutor;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScheduleExecutorTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private ScheduleExecutor executor;

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        TestUtils.setPrivateField(configuration, "scheduleQueueSize", 100);
        TestUtils.setPrivateField(configuration, "scheduleThreads", 2);
        TestUtils.setPrivateField(configuration, "scheduleTickMs", 1000L);
        TestUtils.setPrivateField(configuration, "scheduleRefreshMs", 60000L);
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        for (final var name : fs.listScheduleNames(TestGlobals.DB)) {
            fs.deleteSchedule(TestGlobals.DB, name);
        }
        cache.removeSchedulesForDatabase(TestGlobals.DB);
        registry.clear();
    }

    @AfterEach
    void stop() throws Exception {
        if (executor != null) {
            executor.stop();
            executor = null;
        }
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
    }

    private ScheduleRegistry.Entry register(String name, boolean enabled) throws Exception {
        final var definition = new ScheduleDefinition(name, "p", null, 2000L, null, 0L, enabled, "alice", null, 1L, 1L,
                1L, "alice");
        fs.writeSchedule(TestGlobals.DB, name, eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.DB, name);
        registry.reload(TestGlobals.DB);
        return registry.get(TestGlobals.DB, name);
    }

    @Test
    public void test_fires_a_due_schedule() throws Exception {
        final var entry = register("s", true);
        final var latch = new CountDownLatch(1);
        final var seen = new CopyOnWriteArrayList<String>();
        executor = new ScheduleExecutor();
        executor.start(due -> {
            seen.add(due.getName());
            latch.countDown();
        });
        entry.setNextRunAt(System.currentTimeMillis() - 1);
        executor.tick(System.currentTimeMillis());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("s", seen.getFirst());
        assertEquals(1L, executor.getFired());
        // The next occurrence is computed before the run, so a second tick does not fire it again.
        assertTrue(entry.getNextRunAt() > System.currentTimeMillis());
    }

    @Test
    public void test_does_not_fire_one_that_is_not_due() throws Exception {
        final var entry = register("s", true);
        executor = new ScheduleExecutor();
        executor.start(_ -> fail("should not have fired"));
        entry.setNextRunAt(System.currentTimeMillis() + 60_000);
        executor.tick(System.currentTimeMillis());
        assertEquals(0, executor.getQueued());
    }

    @Test
    public void test_does_not_fire_a_disabled_schedule() throws Exception {
        final var entry = register("s", false);
        executor = new ScheduleExecutor();
        executor.start(_ -> fail("should not have fired"));
        entry.setNextRunAt(System.currentTimeMillis() - 1);
        executor.tick(System.currentTimeMillis());
        assertEquals(0, executor.getQueued());
    }

    @Test
    public void test_does_not_fire_an_unsatisfiable_schedule() throws Exception {
        final var entry = register("s", true);
        executor = new ScheduleExecutor();
        executor.start(_ -> fail("should not have fired"));
        entry.setNextRunAt(0L);
        executor.tick(System.currentTimeMillis());
        assertEquals(0, executor.getQueued());
    }

    // Under clustering a schedule fires only on the node the ring assigns it to. With no ring built there
    // is no owner, so nothing fires here.
    @Test
    public void test_does_not_fire_when_not_the_owner() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        final var entry = register("s", true);
        executor = new ScheduleExecutor();
        executor.start(_ -> fail("should not have fired"));
        entry.setNextRunAt(System.currentTimeMillis() - 1);
        executor.tick(System.currentTimeMillis());
        assertEquals(0, executor.getQueued());
    }

    @Test
    public void test_skips_an_overlapping_run() throws Exception {
        final var entry = register("s", true);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        executor = new ScheduleExecutor();
        executor.start(_ -> {
            started.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        entry.setNextRunAt(System.currentTimeMillis() - 1);
        executor.tick(System.currentTimeMillis());
        assertTrue(started.await(5, TimeUnit.SECONDS));

        entry.setNextRunAt(System.currentTimeMillis() - 1);
        executor.tick(System.currentTimeMillis());
        assertEquals(1L, executor.getSkipped());
        release.countDown();
    }

    @Test
    public void test_drops_the_oldest_on_overflow() throws Exception {
        TestUtils.setPrivateField(configuration, "scheduleQueueSize", 1);
        // A dispatcher without workers: nothing drains the queue, so the second submit really overflows it.
        executor = new ScheduleExecutor();
        TestUtils.setPrivateField(executor, "dispatcher", (Consumer<ScheduleRegistry.Entry>) _ -> {
        });
        executor.submit(register("one", true));
        executor.submit(register("two", true));
        assertEquals(1, executor.getQueued());
        assertEquals(1L, executor.getDropped());
    }

    @Test
    public void test_submit_before_start_is_a_no_op() throws Exception {
        executor = new ScheduleExecutor();
        executor.submit(register("s", true));
        assertEquals(0, executor.getQueued());
        assertEquals(0L, executor.getDropped());
    }

    @Test
    public void test_drain_waits_for_in_flight_runs_and_refuses_new_work() throws Exception {
        final var entry = register("s", true);
        final var started = new CountDownLatch(1);
        final var finished = new CountDownLatch(1);
        executor = new ScheduleExecutor();
        executor.start(_ -> {
            started.countDown();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
        });
        entry.setNextRunAt(System.currentTimeMillis() - 1);
        executor.tick(System.currentTimeMillis());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(executor.drain(5000));
        assertEquals(0, finished.getCount());

        executor.submit(entry);
        assertEquals(0, executor.getQueued());
    }

    // The ticker thread itself, at a tick short enough to observe: it must both refresh the registry from
    // disk and fire what is due without anybody calling tick() by hand.
    @Test
    public void test_the_ticker_refreshes_the_registry_and_fires_what_is_due() throws Exception {
        TestUtils.setPrivateField(configuration, "scheduleTickMs", 20L);
        TestUtils.setPrivateField(configuration, "scheduleRefreshMs", 20L);
        register("s", true);
        final var latch = new CountDownLatch(1);
        executor = new ScheduleExecutor();
        executor.start(_ -> latch.countDown());
        registry.get(TestGlobals.DB, "s").setNextRunAt(System.currentTimeMillis() - 1);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    // A run that outlives the budget is abandoned rather than waited on: schedules are at-most-once, so
    // the next occurrence simply fires normally.
    @Test
    public void test_drain_gives_up_on_a_run_that_outlives_the_budget() throws Exception {
        final var entry = register("s", true);
        final var started = new CountDownLatch(1);
        executor = new ScheduleExecutor();
        executor.start(_ -> {
            started.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        entry.setNextRunAt(System.currentTimeMillis() - 1);
        executor.tick(System.currentTimeMillis());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertFalse(executor.drain(100));
    }

    @Test
    public void test_counters_are_exposed() {
        executor = new ScheduleExecutor();
        executor.countFailure();
        executor.countSkip();
        assertEquals(1L, executor.getFailed());
        assertEquals(1L, executor.getSkipped());
        assertEquals(0L, executor.getFired());
        assertEquals(0, executor.pending());
    }
}
