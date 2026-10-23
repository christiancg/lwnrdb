package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.config.Configuration;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CheckpointGuardTest {
    private static final String DIRTY_MARKER = TestGlobals.COLL + "-indexes.dirty";
    private static final String LOG_REWRITE = TestGlobals.COLL + ".wal.repair";
    private static final long SHIPPED_CHECKPOINT_BYTES = 8L * 1024 * 1024;
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "name"));
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", SHIPPED_CHECKPOINT_BYTES);
        recorder.close();
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String id) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("name", "x");
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(object);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
    }

    private void checkpointEveryWrite() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", 1L);
    }

    private Set<String> idsTheReplayReports() throws Exception {
        return fs.redoWriteAheadLogs().stream().filter(
                row -> TestGlobals.DB.equals(row.getDatabaseName()) && TestGlobals.COLL.equals(row.getCollectionName()))
                .map(PkIndexEntry::getValue).collect(Collectors.toSet());
    }

    @Test
    public void test_a_checkpoint_with_pending_ids_logs_them_instead_of_writing_a_marker() throws Exception {
        checkpointEveryWrite();

        save("pending");

        assertFalse(recorder.forcedNames().contains(DIRTY_MARKER), recorder.forcedNames().toString());
        assertTrue(recorder.forcedNames().contains(LOG_REWRITE), recorder.forcedNames().toString());
        assertEquals(Set.of("pending"), idsTheReplayReports(),
                "the checkpoint dropped the record, so its log must still name the id for startup to repair");
    }

    @Test
    public void test_a_checkpoint_with_nothing_pending_writes_no_marker() throws Exception {
        save("indexed");
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        recorder.clear();

        fs.checkpointWriteAheadLogs(Long.MAX_VALUE);

        assertTrue(recorder.forcedNames().contains(LOG_REWRITE), recorder.forcedNames().toString());
        assertFalse(recorder.forcedNames().contains(DIRTY_MARKER), recorder.forcedNames().toString());
        assertEquals(Set.of(), idsTheReplayReports());
    }

    @Test
    public void test_an_id_still_pending_is_carried_into_every_later_checkpoint() throws Exception {
        checkpointEveryWrite();
        save("first");

        save("second");

        assertEquals(Set.of("first", "second"), idsTheReplayReports());
    }

    @Test
    public void test_an_id_whose_index_work_finished_leaves_the_next_checkpoint() throws Exception {
        checkpointEveryWrite();
        save("drained");
        pendingIndexWrites.clear(TestGlobals.DB, TestGlobals.COLL, "drained", Long.MAX_VALUE);

        save("later");

        assertEquals(Set.of("later"), idsTheReplayReports());
    }

    @Test
    public void test_shutdown_keeps_the_log_of_a_collection_with_pending_ids() {
        save("pending");
        recorder.clear();

        fs.checkpointWriteAheadLogs(Long.MAX_VALUE);

        assertFalse(recorder.forcedNames().contains(LOG_REWRITE), recorder.forcedNames().toString());
        assertFalse(recorder.forcedNames().contains(DIRTY_MARKER), recorder.forcedNames().toString());
    }

    @Test
    public void test_a_second_instance_does_not_take_over_the_guard() throws Exception {
        assertTrue(new PendingIndexWrites().idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
        checkpointEveryWrite();

        save("pending");

        assertEquals(Set.of("pending"), idsTheReplayReports(),
                "the checkpoint must ask the published singleton, which holds the pending id, not the latest instance");
    }
}
