package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScheduleRegistryTest {
    private static final String OTHER_DB = "otherDb";
    private static final String DROPPED_DB = "droppedDb";
    private static final long RACE_WAIT_SECONDS = 5L;
    private static final long MUTATION_HEAD_START_MILLIS = 300L;
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(OTHER_DB));
        IocContainer.get(FileSystem.class).createDatabaseFolder(OTHER_DB);
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
        for (final var dbName : new String[]{TestGlobals.DB, OTHER_DB}) {
            for (final var name : fs.listScheduleNames(dbName)) {
                fs.deleteSchedule(dbName, name);
            }
            cache.removeSchedulesForDatabase(dbName);
        }
        registry.clear();
    }

    private interface Mutation {
        void run() throws Exception;
    }

    private void writeSchedule(String dbName, String name, String cron, long intervalMs) throws Exception {
        writeSchedule(dbName, name, cron, intervalMs, true);
    }

    private void writeSchedule(String dbName, String name, String cron, long intervalMs, boolean enabled)
            throws Exception {
        final var definition = new ScheduleDefinition(name, "p", cron, intervalMs, null, 0L, enabled, "alice", null, 1L,
                1L, 1L, "alice");
        fs.writeSchedule(dbName, name, eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(dbName, name);
    }

    private void writeCorruptSchedule(String name) throws Exception {
        fs.writeSchedule(TestGlobals.DB, name, "{\"name\": \"" + name);
        cache.removeSchedule(TestGlobals.DB, name);
    }

    @Test
    public void test_an_unreadable_definition_is_skipped_and_its_siblings_still_load() throws Exception {
        writeSchedule(TestGlobals.DB, "good", null, 2000L);
        writeCorruptSchedule("torn");

        assertDoesNotThrow(registry::loadAll, "one torn file must not stop the node booting");

        assertNotNull(registry.get(TestGlobals.DB, "good"));
        assertNull(registry.get(TestGlobals.DB, "torn"), "an unreadable definition is not registered");
    }

    @Test
    public void test_an_unreadable_definition_does_not_unregister_a_working_entry() throws Exception {
        writeSchedule(TestGlobals.DB, "flaky", null, 2000L);
        registry.loadAll();
        assertNotNull(registry.get(TestGlobals.DB, "flaky"));

        writeCorruptSchedule("flaky");
        registry.reload(TestGlobals.DB);

        assertNotNull(registry.get(TestGlobals.DB, "flaky"),
                "a read failure is not an absence, so the entry must survive the reload's removeIf");
    }

    @Test
    public void test_an_interval_run_advances_from_the_scheduled_instant() throws Exception {
        writeSchedule(TestGlobals.DB, "ticker", null, 60_000L);
        registry.loadAll();
        final var entry = registry.get(TestGlobals.DB, "ticker");
        assertNotNull(entry);
        entry.setNextRunAt(1_000_000L);

        assertEquals(1_060_000L, registry.nextRunAfter(entry, 1_000_500L),
                "folding the tick's latency into every period loses fires over a day");
    }

    @Test
    public void test_an_interval_run_skips_rather_than_storms_after_downtime() throws Exception {
        writeSchedule(TestGlobals.DB, "ticker", null, 60_000L);
        registry.loadAll();
        final var entry = registry.get(TestGlobals.DB, "ticker");
        assertNotNull(entry);
        entry.setNextRunAt(1_000_000L);

        final var next = registry.nextRunAfter(entry, 4_600_000L);

        assertTrue(next > 4_600_000L, "the missed hour must not queue up sixty catch-up runs");
        assertEquals(4_660_000L, next);
    }

    @Test
    public void test_a_first_interval_run_is_one_period_from_now() throws Exception {
        writeSchedule(TestGlobals.DB, "fresh", null, 60_000L);
        registry.loadAll();
        final var entry = registry.get(TestGlobals.DB, "fresh");
        assertNotNull(entry);

        assertTrue(entry.getNextRunAt() > System.currentTimeMillis() + 50_000L);
    }

    @Test
    public void test_load_all_picks_up_every_database() throws Exception {
        writeSchedule(TestGlobals.DB, "a", null, 2000L);
        writeSchedule(OTHER_DB, "b", "0 3 * * *", 0L);
        registry.loadAll();
        assertEquals(2, registry.size());
        assertNotNull(registry.get(TestGlobals.DB, "a"));
        assertNotNull(registry.get(OTHER_DB, "b"));
    }

    @Test
    public void test_reload_replaces_one_database_only() throws Exception {
        writeSchedule(TestGlobals.DB, "a", null, 2000L);
        writeSchedule(OTHER_DB, "b", null, 2000L);
        registry.loadAll();

        fs.deleteSchedule(TestGlobals.DB, "a");
        cache.removeSchedule(TestGlobals.DB, "a");
        registry.reload(TestGlobals.DB);
        assertNull(registry.get(TestGlobals.DB, "a"));
        assertNotNull(registry.get(OTHER_DB, "b"));
    }

    @Test
    public void test_remove_database_drops_its_entries() throws Exception {
        writeSchedule(TestGlobals.DB, "a", null, 2000L);
        writeSchedule(OTHER_DB, "b", null, 2000L);
        registry.loadAll();
        registry.removeDatabase(TestGlobals.DB);
        assertNull(registry.get(TestGlobals.DB, "a"));
        assertNotNull(registry.get(OTHER_DB, "b"));
    }

    @Test
    public void test_next_run_at_is_in_the_future_after_load() throws Exception {
        writeSchedule(TestGlobals.DB, "a", null, 2000L);
        writeSchedule(TestGlobals.DB, "b", "0 3 * * *", 0L);
        registry.loadAll();
        final var now = System.currentTimeMillis();
        assertTrue(registry.get(TestGlobals.DB, "a").getNextRunAt() > now);
        assertTrue(registry.get(TestGlobals.DB, "b").getNextRunAt() > now);
    }

    @Test
    public void test_reload_preserves_the_next_run_of_an_unchanged_schedule() throws Exception {
        writeSchedule(TestGlobals.DB, "a", null, 2000L);
        registry.loadAll();
        final var first = registry.get(TestGlobals.DB, "a").getNextRunAt();
        registry.reload(TestGlobals.DB);
        assertEquals(first, registry.get(TestGlobals.DB, "a").getNextRunAt());
    }

    @Test
    public void test_an_unsatisfiable_cron_yields_no_next_run() throws Exception {
        writeSchedule(TestGlobals.DB, "a", "0 0 30 2 *", 0L);
        registry.loadAll();
        assertEquals(0L, registry.get(TestGlobals.DB, "a").getNextRunAt());
    }

    @Test
    public void test_an_unparseable_cron_is_not_registered() throws Exception {
        writeSchedule(TestGlobals.DB, "a", "not a cron", 0L);
        registry.loadAll();
        assertNull(registry.get(TestGlobals.DB, "a"));
    }

    @Test
    public void test_entries_exposes_the_definition_and_key() throws Exception {
        writeSchedule(TestGlobals.DB, "a", null, 2000L);
        registry.loadAll();
        final var entry = registry.entries().iterator().next();
        assertEquals(TestGlobals.DB, entry.getDbName());
        assertEquals("a", entry.getName());
        assertEquals(TestGlobals.DB + "|a", entry.key());
        assertNull(entry.getCron());
        assertEquals("p", entry.getDefinition().getProcedureName());
    }

    private void refreshRacing(String dbName, Mutation mutation) throws Exception {
        final var definitionRead = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var firstRead = new AtomicBoolean(true);
        final var stalledCache = Mockito.spy(cache);
        Mockito.doAnswer(invocation -> {
            final var definition = invocation.callRealMethod();
            if (firstRead.compareAndSet(true, false)) {
                definitionRead.countDown();
                assertTrue(release.await(RACE_WAIT_SECONDS, TimeUnit.SECONDS));
            }
            return definition;
        }).when(stalledCache).getSchedule(Mockito.anyString(), Mockito.anyString());
        TestUtils.setPrivateField(registry, "cache", stalledCache);
        try {
            final var refresh = Thread.ofPlatform().start(() -> registry.reload(dbName));
            assertTrue(definitionRead.await(RACE_WAIT_SECONDS, TimeUnit.SECONDS));
            final var failure = new Exception[1];
            final var mutator = Thread.ofPlatform().start(() -> {
                try {
                    mutation.run();
                } catch (Exception e) {
                    failure[0] = e;
                }
            });
            mutator.join(MUTATION_HEAD_START_MILLIS);
            release.countDown();
            refresh.join(TimeUnit.SECONDS.toMillis(RACE_WAIT_SECONDS));
            mutator.join(TimeUnit.SECONDS.toMillis(RACE_WAIT_SECONDS));
            assertNull(failure[0]);
        } finally {
            TestUtils.setPrivateField(registry, "cache", cache);
        }
    }

    @Test
    public void test_a_refresh_that_read_a_definition_before_its_delete_does_not_reinstate_it() throws Exception {
        writeSchedule(TestGlobals.DB, "deleted", null, 2000L);
        registry.loadAll();

        refreshRacing(TestGlobals.DB, () -> {
            fs.deleteSchedule(TestGlobals.DB, "deleted");
            cache.removeSchedule(TestGlobals.DB, "deleted");
            registry.reload(TestGlobals.DB);
        });

        assertNull(registry.get(TestGlobals.DB, "deleted"),
                "an acknowledged DELETE_SCHEDULE must not be undone by a refresh that read the old file");
    }

    @Test
    public void test_a_refresh_racing_a_disable_does_not_re_enable() throws Exception {
        writeSchedule(TestGlobals.DB, "paused", null, 2000L);
        registry.loadAll();

        refreshRacing(TestGlobals.DB, () -> {
            writeSchedule(TestGlobals.DB, "paused", null, 2000L, false);
            registry.reload(TestGlobals.DB);
        });

        assertFalse(registry.get(TestGlobals.DB, "paused").getDefinition().isEnabled(),
                "the disabled definition saved last must be the one registered");
    }

    @Test
    public void test_a_refresh_racing_drop_database_leaves_no_entry() throws Exception {
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(DROPPED_DB));
        fs.createDatabaseFolder(DROPPED_DB);
        writeSchedule(DROPPED_DB, "ghost", null, 2000L);
        registry.loadAll();

        refreshRacing(DROPPED_DB, () -> {
            fs.deleteDatabase(DROPPED_DB);
            cache.evictDatabase(DROPPED_DB);
            AdminOperationHelper.deleteDatabaseEntry(DROPPED_DB);
            registry.removeDatabase(DROPPED_DB);
        });
        registry.loadAll();

        assertNull(registry.get(DROPPED_DB, "ghost"),
                "a dropped database's schedule must not outlive the drop until the next restart");
    }
}
