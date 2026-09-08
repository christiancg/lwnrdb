package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.AdminCache;
import org.techhouse.cache.BoundedLruCache;
import org.techhouse.cache.Cache;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The bounds and the miss-cache separation of the admin metadata caches. Each test installs its own
 * small-capped caches so eviction is reachable without writing megabytes.
 */
public class AdminCacheBoundsTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final AdminCache adminCache = IocContainer.get(AdminCache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() {
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        cache.removeCollectionSchemasForDatabase(TestGlobals.DB);
    }

    @AfterEach
    void restoreDefaults() throws Exception {
        installProcedureCache(32L * 1024 * 1024);
        installMissCache(4096);
        installTriggerCache(4096);
    }

    private void installProcedureCache(long maxBytes) throws Exception {
        TestUtils.setPrivateField(adminCache, "procedures", new BoundedLruCache<ProcedureDefinition>(Integer.MAX_VALUE,
                maxBytes, definition -> (long) definition.getSource().length() * 2L + 512L));
    }

    private void installMissCache(int maxEntries) throws Exception {
        TestUtils.setPrivateField(adminCache, "metadataMisses", new BoundedLruCache<Boolean>(maxEntries, 0L, _ -> 1L));
    }

    private void installTriggerCache(int maxEntries) throws Exception {
        TestUtils.setPrivateField(adminCache, "triggers", new BoundedLruCache<List<TriggerDefinition>>(maxEntries, 0L,
                definitions -> definitions.size() * 512L + 128L));
    }

    private ProcedureDefinition writeProcedure(String name, String source) throws Exception {
        final var definition = new ProcedureDefinition(name, source, 1L, null, true, 1L, 1L, "alice");
        fs.writeProcedure(TestGlobals.DB, name, eJson.toJson(definition.toJsonObject()));
        return definition;
    }

    private void writeTriggers() throws Exception {
        final var definition = new TriggerDefinition("t1", java.util.Set.of(EventType.CREATED), "proc",
                TriggerDefinition.MODE_DOCUMENT, false, true, "alice", 1L, 1L, 1L, "alice");
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL,
                eJson.toJson(TriggerDefinition.toFileJson(List.of(definition))));
    }

    @Test
    public void test_procedure_cache_evicts_under_byte_cap() throws Exception {
        installProcedureCache(2048L);
        writeProcedure("p1", "x".repeat(400));
        writeProcedure("p2", "y".repeat(400));
        writeProcedure("p3", "z".repeat(400));
        assertNotNull(cache.getProcedure(TestGlobals.DB, "p1"));
        assertNotNull(cache.getProcedure(TestGlobals.DB, "p2"));
        assertNotNull(cache.getProcedure(TestGlobals.DB, "p3"));
        final var stats = cache.metadataCacheStats();
        assertTrue(stats.procedureBytes() <= 2048L, "expected the cache to stay under its byte cap");
        assertTrue(stats.procedureEntries() < 3, "expected at least one procedure to have been evicted");
        // Evicted, not lost: the next read comes back from disk.
        assertNotNull(cache.getProcedure(TestGlobals.DB, "p1"));
    }

    // The CALL_PROCEDURE vector: a caller naming procedures that do not exist must not be able to push the
    // ones actually in use out of the cache.
    @Test
    public void test_miss_flood_does_not_evict_live_procedures() throws Exception {
        installProcedureCache(32L * 1024 * 1024);
        installMissCache(16);
        writeProcedure("live", "return 1;");
        assertNotNull(cache.getProcedure(TestGlobals.DB, "live"));
        for (var i = 0; i < 500; i++) {
            assertNull(cache.getProcedure(TestGlobals.DB, "ghost" + i));
        }
        assertEquals(1, cache.metadataCacheStats().procedureEntries());
    }

    @Test
    public void test_miss_cache_is_itself_bounded() throws Exception {
        installMissCache(16);
        for (var i = 0; i < 500; i++) {
            assertNull(cache.getProcedure(TestGlobals.DB, "ghost" + i));
        }
        assertTrue(cache.metadataCacheStats().missEntries() <= 16,
                "expected the miss cache to stay at its cap, was " + cache.metadataCacheStats().missEntries());
    }

    // A remembered miss must not outlive the thing it recorded the absence of.
    @Test
    public void test_remove_procedure_clears_the_miss_entry() throws Exception {
        assertNull(cache.getProcedure(TestGlobals.DB, "later"));
        writeProcedure("later", "return 1;");
        cache.removeProcedure(TestGlobals.DB, "later");
        assertNotNull(cache.getProcedure(TestGlobals.DB, "later"));
    }

    @Test
    public void test_put_procedure_clears_the_miss_entry() throws Exception {
        assertNull(cache.getProcedure(TestGlobals.DB, "fresh"));
        final var definition = writeProcedure("fresh", "return 1;");
        cache.putProcedure(TestGlobals.DB, definition);
        assertEquals(definition, cache.getProcedure(TestGlobals.DB, "fresh"));
    }

    @Test
    public void test_remove_procedures_for_database_clears_miss_entries() throws Exception {
        assertNull(cache.getProcedure(TestGlobals.DB, "gone"));
        writeProcedure("gone", "return 1;");
        cache.removeProceduresForDatabase(TestGlobals.DB);
        assertNotNull(cache.getProcedure(TestGlobals.DB, "gone"));
    }

    @Test
    public void test_schema_miss_is_cleared_by_put() {
        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
        final var schema = new JsonObject();
        schema.add("type", new JsonString("object"));
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, schema);
        assertEquals(schema, cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_schema_and_procedure_misses_do_not_collide() {
        // Both miss caches share one store, so their keys must be namespaced apart: a missing procedure named
        // like the collection must not be reported as a missing schema.
        assertNull(cache.getProcedure(TestGlobals.DB, TestGlobals.COLL));
        final var schema = new JsonObject();
        schema.add("type", new JsonString("object"));
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, schema);
        assertEquals(schema, cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
        assertNull(cache.getProcedure(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_trigger_cache_evicts_under_entry_cap() throws Exception {
        installTriggerCache(1);
        writeTriggers();
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
        cache.getTriggersFor(TestGlobals.DB, "other");
        assertEquals(1, cache.metadataCacheStats().triggerEntries());
        // Evicted, then reloaded from its file rather than answered as "no triggers".
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_metadata_cache_stats_report_the_live_footprint() throws Exception {
        writeProcedure("counted", "return 1;");
        assertNotNull(cache.getProcedure(TestGlobals.DB, "counted"));
        final var stats = cache.metadataCacheStats();
        assertEquals(1, stats.procedureEntries());
        assertTrue(stats.procedureBytes() > 0L);
    }
}
