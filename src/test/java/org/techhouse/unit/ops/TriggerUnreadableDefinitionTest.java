package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerUnreadableDefinitionTest {
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
        IocContainer.get(Cache.class).removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() {
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
    }

    private File triggersFile() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL
                + File.separator + TestGlobals.COLL + "-triggers.json");
    }

    private void writeTriggers(TriggerDefinition definition) throws java.io.IOException {
        final var wrapper = new org.techhouse.ejson.elements.JsonObject();
        final var array = new org.techhouse.ejson.elements.JsonArray();
        array.add(definition.toJsonObject());
        wrapper.add("triggers", array);
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, eJson.toJson(wrapper));
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
    }

    private static TriggerDefinition definition() {
        return new TriggerDefinition("t1", new LinkedHashSet<>(Set.of(EventType.CREATED)), "p",
                TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner");
    }

    private static TriggerEvent eventFor(String runId) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "t1", "p", false, List.of(),
                "owner", 0, runId);
    }

    private static String recordRun() {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "t1", "p",
                EventType.CREATED, false, "owner", 0, System.currentTimeMillis(), List.of()));
    }

    @Test
    public void test_an_unreadable_trigger_file_keeps_the_pending_run() throws Exception {
        writeTriggers(definition());
        final var runId = recordRun();
        org.junit.jupiter.api.Assumptions.assumeTrue(runId != null, "the run log must be enabled for this case");
        final var file = triggersFile();
        assertTrue(file.delete());
        assertTrue(file.mkdirs(), "a directory in the file's place makes the read fail rather than report absence");
        try {
            TriggerDispatcher.dispatch(eventFor(runId));

            assertFalse(TriggerRunLog.recordIdsFor(runId).isEmpty(),
                    "the dispatcher cannot tell an unreadable definition from a deleted one, and consuming the"
                            + " record deletes the only thing that would have replayed the run");
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void test_a_genuinely_deleted_trigger_still_consumes_the_run() {
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        final var runId = recordRun();
        org.junit.jupiter.api.Assumptions.assumeTrue(runId != null, "the run log must be enabled for this case");

        TriggerDispatcher.dispatch(eventFor(runId));

        assertTrue(TriggerRunLog.recordIdsFor(runId).isEmpty(),
                "a trigger that really is gone must still consume its pending run");
    }
}
