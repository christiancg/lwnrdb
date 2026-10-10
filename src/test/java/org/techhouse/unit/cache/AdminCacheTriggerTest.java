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
    void clear() throws Exception {
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
    public void test_a_trigger_file_that_cannot_be_parsed_is_not_an_empty_one() throws Exception {
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, "definitely not json");
        assertThrows(org.techhouse.ex.MetadataReadException.class,
                () -> cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL),
                "a file that exists but cannot be turned into definitions is a read failure, not an absence:"
                        + " answering with an empty list silently skips every before-write hook and lets the"
                        + " next SAVE_TRIGGER rewrite the file from what it could not read");
    }

    @Test
    public void test_one_unparseable_trigger_does_not_hide_the_others() throws Exception {
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL,
                "{\"triggers\":[{\"name\":\"good\",\"events\":[\"CREATED\"],\"procedureName\":\"p\"},"
                        + "{\"name\":\"bad\",\"events\":[\"BOGUS\"],\"procedureName\":\"p\"}]}");
        assertThrows(org.techhouse.ex.MetadataReadException.class,
                () -> cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL),
                "fromFileJson is all-or-nothing, so one bad element empties the whole list - reporting that as"
                        + " 'no triggers' would disable the definitions that parsed perfectly well");
    }

    @Test
    public void test_an_absent_trigger_file_is_still_an_absence() {
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "a collection that simply has no triggers must not be refused");
    }

    @Test
    public void test_a_blank_trigger_file_is_still_an_absence() throws Exception {
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, "   ");
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "an empty file is what an interrupted delete leaves behind, and it names no triggers to skip");
    }

    @Test
    public void test_a_parse_failure_is_not_published_into_the_cache() throws Exception {
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, "definitely not json");
        assertThrows(org.techhouse.ex.MetadataReadException.class,
                () -> cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));

        writeTriggers(definition("repaired"));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size(),
                "the failure must not be remembered as an empty list, or repairing the file on disk leaves the"
                        + " collection firing nothing until eviction or restart");
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
