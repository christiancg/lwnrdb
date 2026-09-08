package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private void writeSchedule(String dbName, String name, String cron, long intervalMs) throws Exception {
        final var definition = new ScheduleDefinition(name, "p", cron, intervalMs, null, 0L, true, "alice", null, 1L,
                1L, 1L, "alice");
        fs.writeSchedule(dbName, name, eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(dbName, name);
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

    // Reloading an unchanged definition must not push the next occurrence out, otherwise the periodic
    // refresh would starve a schedule whose interval is shorter than scheduleRefreshMs.
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

    // A definition whose cron this version cannot parse is dropped rather than fired on a guess.
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
}
