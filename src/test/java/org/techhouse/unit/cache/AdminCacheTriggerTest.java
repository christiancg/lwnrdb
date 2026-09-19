package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminCacheTriggerTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        // Leave no cached trigger behind: another class's procedure delete would hit the reference check
        IocContainer.get(Cache.class).removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() {
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
    }

    private static TriggerDefinition definition(String name) {
        return new TriggerDefinition(name, new LinkedHashSet<>(Set.of(EventType.CREATED)), "p",
                TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner");
    }

    private void writeTriggers(TriggerDefinition... definitions) throws Exception {
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL,
                eJson.toJson(TriggerDefinition.toFileJson(List.of(definitions))));
    }

    @Test
    public void test_get_triggers_for_returns_empty_for_untriggered_collection() throws Exception {
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
        writeTriggers(definition("later"));
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_get_triggers_for_loads_lazily_from_disk() throws Exception {
        writeTriggers(definition("first"), definition("second"));
        final var loaded = cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(2, loaded.size());
        assertEquals("first", loaded.getFirst().getName());
    }

    @Test
    public void test_put_triggers_replaces_the_list() {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(definition("a")));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(definition("a"), definition("b")));
        assertEquals(2, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
    }

    @Test
    public void test_remove_triggers_for_database_forgets_the_collection() throws Exception {
        writeTriggers(definition("a"));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of());
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_malformed_trigger_file_reads_as_no_triggers() throws Exception {
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, "definitely not json");
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    private static java.io.File triggersFile() {
        return new java.io.File(TestGlobals.PATH + java.io.File.separator + TestGlobals.DB + java.io.File.separator
                + TestGlobals.COLL + java.io.File.separator + TestGlobals.COLL + "-triggers.json");
    }

    @Test
    public void test_a_read_failure_is_not_cached_as_absence() throws Exception {
        writeTriggers(definition("kept"));
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        final var file = triggersFile();
        assertTrue(file.delete());
        assertTrue(file.mkdirs(), "a directory in the file's place makes the read fail rather than report absence");
        try {
            assertThrows(org.techhouse.ex.MetadataReadException.class,
                    () -> cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));
        } finally {
            assertTrue(file.delete());
        }

        writeTriggers(definition("kept"));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size(),
                "a failed read must not be remembered as a definitive absence, or every later write on this"
                        + " collection silently fires no triggers until eviction or restart");
    }

    @Test
    public void test_a_read_failure_does_not_erase_the_trigger_file() throws Exception {
        writeTriggers(definition("kept"));
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        final var file = triggersFile();
        final var original = java.nio.file.Files.readString(file.toPath());
        assertTrue(file.delete());
        assertTrue(file.mkdirs());
        try {
            assertThrows(org.techhouse.ex.MetadataReadException.class,
                    () -> cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));
        } finally {
            assertTrue(file.delete());
        }
        java.nio.file.Files.writeString(file.toPath(), original);

        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
    }
}
