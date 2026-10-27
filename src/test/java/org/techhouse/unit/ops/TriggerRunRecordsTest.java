package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.admin.TriggerRunRecords;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class TriggerRunRecordsTest {
    private static final String RUNS_WAL = Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME + ".wal";
    private final Cache cache = IocContainer.get(Cache.class);
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerRunLogEnabled", true);
        TriggerTestSupport.consumeAllPendingRuns();
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
        TriggerTestSupport.consumeAllPendingRuns();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static List<AdminTriggerRunEntry> stagedChunks(int ids) {
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < ids; i++) {
            final var data = new JsonObject();
            data.add(Globals.PK_FIELD, new JsonString("an-id-long-enough-to-fill-a-record-quickly-" + i));
            entries.add(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data));
        }
        final var chunks = new ArrayList<AdminTriggerRunEntry>();
        final var runId = TriggerRunLog.recordStaged(
                new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit", "recalc",
                        EventType.CREATED, true, "alice", 0, System.currentTimeMillis(), entries),
                Map.of(), chunks::add);
        assertNotNull(runId);
        return chunks;
    }

    private long runsWalForces() {
        return recorder.forcedNames().stream().filter(RUNS_WAL::equals).count();
    }

    @Test
    public void test_a_batch_lands_as_one_record_and_publishes_every_row() throws Exception {
        TriggerRunRecords.insert(stagedChunks(1));
        recorder.clear();
        final var chunks = stagedChunks(20_000);
        assertTrue(chunks.size() > 1, "the run spans several chunks");

        TriggerRunRecords.insert(chunks);

        assertEquals(1L, runsWalForces(), "one write-ahead log record for the whole batch");
        for (final var chunk : chunks) {
            assertNotNull(cache.getPkIndexTriggerRun(chunk.get_id()), "the row is published after it landed");
        }
        assertEquals(chunks.size() + 1, TriggerRunLog.pending().size());
    }

    @Test
    public void test_a_batch_beyond_one_record_is_written_in_chunks() throws Exception {
        TriggerRunRecords.insert(stagedChunks(1));
        recorder.clear();
        final var chunks = stagedChunks(120_000);

        TriggerRunRecords.insert(chunks);

        assertTrue(runsWalForces() > 1L, "a batch past the record size spans several records");
        assertTrue(runsWalForces() < chunks.size(), "still far fewer records than rows");
        assertEquals(chunks.size() + 1, TriggerRunLog.pending().size());
    }

    @Test
    public void test_an_empty_batch_writes_nothing() throws Exception {
        TriggerRunRecords.insert(List.of());

        assertEquals(0L, runsWalForces());
        assertEquals(List.of(), TriggerRunLog.pending());
    }
}
