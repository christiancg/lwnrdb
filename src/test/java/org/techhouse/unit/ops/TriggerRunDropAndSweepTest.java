package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class TriggerRunDropAndSweepTest {
    private static final String DROPPED_COLL = "droppedColl";
    private static final String DROPPED_DB = "droppedDb";
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

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
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TriggerTestSupport.consumeAllPendingRuns();
    }

    @AfterEach
    void restore() throws Exception {
        for (final var key : List.of(ConfigKey.TRIGGER_RUN_RETENTION_MS, ConfigKey.TRIGGER_DEAD_LETTER_RETENTION_MS)) {
            TestUtils.setPrivateField(configuration, key.key(), key.defaultValue());
        }
    }

    private static String recordRun(String dbName, String collName, String id) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        final var entry = new DbEntry();
        entry.setDatabaseName(dbName);
        entry.setCollectionName(collName);
        entry.set_id(id);
        entry.setData(data);
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(dbName, collName, "audit", "recalc",
                EventType.DELETED, false, "alice", 0, System.currentTimeMillis(), List.of(entry)));
    }

    @Test
    public void test_dropping_a_collection_removes_its_pending_trigger_runs() throws Exception {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, DROPPED_COLL)).getStatus());
        final var droppedRun = recordRun(TestGlobals.DB, DROPPED_COLL, "old-tenant-doc");
        final var keptRun = recordRun(TestGlobals.DB, TestGlobals.COLL, "unrelated");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, DROPPED_COLL)).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, DROPPED_COLL)).getStatus());

        assertNull(TriggerTestSupport.pendingRun(droppedRun),
                "a re-created same-name collection must never receive the dropped one's deleted documents");
        assertNotNull(TriggerTestSupport.pendingRun(keptRun));
    }

    @Test
    public void test_dropping_a_database_removes_its_pending_trigger_runs() throws Exception {
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateDatabaseRequest(DROPPED_DB)).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(DROPPED_DB, DROPPED_COLL)).getStatus());
        final var droppedRun = recordRun(DROPPED_DB, DROPPED_COLL, "old-tenant-doc");

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(DROPPED_DB)).getStatus());

        assertNull(TriggerTestSupport.pendingRun(droppedRun));
    }

    @Test
    public void test_dead_trigger_rows_are_collected_by_the_standalone_sweep() throws Exception {
        final var deadRun = recordRun(TestGlobals.DB, TestGlobals.COLL, "dead-letter");
        TriggerRunLog.markAttempt(deadRun, TriggerRunStatus.DEAD, 3, "Error: boom", 0L);
        final var pendingRun = recordRun(TestGlobals.DB, TestGlobals.COLL, "still-pending");
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 1L);
        TestUtils.setPrivateField(configuration, "triggerDeadLetterRetentionMs", 1L);
        Thread.sleep(10L);

        TriggerRunRecovery.sweep();

        assertNull(TriggerTestSupport.pendingRun(deadRun), "standalone used to collect dead letters only at startup");
        assertNotNull(TriggerTestSupport.pendingRun(pendingRun), "this node's own pending run is never collected");
    }
}
