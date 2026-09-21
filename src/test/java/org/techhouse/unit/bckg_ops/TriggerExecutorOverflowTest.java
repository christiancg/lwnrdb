package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerExecutorOverflowTest {
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerQueueSize", 1000);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerQueueSize", 1);
        TestUtils.setPrivateField(configuration, "maxEntrySize", 1_048_576L);
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    private static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }

    private static String recordRun(String id) {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "recalc", EventType.CREATED, false, "alice", 0, System.currentTimeMillis(), List.of(entry(id))));
    }

    private static AdminTriggerRunEntry firstChunk(String runId) throws Exception {
        for (final var entry : TriggerRunLog.pending()) {
            if (runId.equals(entry.getRunId())) {
                return entry;
            }
        }
        return null;
    }

    private static TriggerExecutor executorThatNeverDrains() throws Exception {
        final var executor = new TriggerExecutor();
        TestUtils.setPrivateField(executor, "dispatcher", (Consumer<TriggerEvent>) _ -> {
        });
        return executor;
    }

    private static TriggerEvent eventFor(String id, String runId) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "recalc", false,
                List.of(entry(id)), "alice", 0, runId, 1);
    }

    @Test
    public void test_a_dropped_run_is_dead_lettered_rather_than_deleted() throws Exception {
        final var executor = executorThatNeverDrains();
        final var evictedRun = recordRun("evicted");
        executor.submit(eventFor("evicted", evictedRun));

        executor.submit(eventFor("kept", recordRun("kept")));

        final var record = firstChunk(evictedRun);
        assertNotNull(record, "deleting the record is the one place the run log is thrown away rather than used");
        assertEquals(TriggerRunStatus.DEAD, record.getStatus());
    }

    @Test
    public void test_the_dropped_run_records_why_it_was_dropped() throws Exception {
        final var executor = executorThatNeverDrains();
        final var evictedRun = recordRun("why");
        executor.submit(eventFor("why", evictedRun));

        executor.submit(eventFor("kept", recordRun("kept2")));

        final var record = firstChunk(evictedRun);
        assertNotNull(record);
        assertEquals("dropped: trigger queue full", record.getLastError());
    }

    @Test
    public void test_the_dropped_run_keeps_its_payload_so_it_stays_replayable() throws Exception {
        final var executor = executorThatNeverDrains();
        final var evictedRun = recordRun("replayable");
        executor.submit(eventFor("replayable", evictedRun));

        executor.submit(eventFor("kept", recordRun("kept3")));

        final var record = firstChunk(evictedRun);
        assertNotNull(record);
        assertEquals(List.of("replayable"), record.getIds());
    }
}
