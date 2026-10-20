package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.config.Configuration;
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

    @Test
    public void test_a_checkpoint_with_pending_ids_forces_the_marker_before_it_rewrites_the_log() throws Exception {
        checkpointEveryWrite();

        save("pending");

        final var forced = recorder.forcedNames();
        assertTrue(forced.contains(DIRTY_MARKER), forced.toString());
        assertTrue(forced.indexOf(DIRTY_MARKER) < forced.lastIndexOf(LOG_REWRITE), forced.toString());
    }

    @Test
    public void test_a_checkpoint_with_nothing_pending_writes_no_marker() {
        save("indexed");
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        recorder.clear();

        fs.checkpointWriteAheadLogs();

        assertTrue(recorder.forcedNames().contains(LOG_REWRITE), recorder.forcedNames().toString());
        assertFalse(recorder.forcedNames().contains(DIRTY_MARKER), recorder.forcedNames().toString());
    }

    @Test
    public void test_a_checkpoint_whose_marker_cannot_be_forced_keeps_the_log() throws Exception {
        save("first");
        checkpointEveryWrite();
        recorder.failingFor("-indexes.dirty");
        recorder.clear();

        save("pending");

        assertFalse(recorder.forcedNames().contains(LOG_REWRITE),
                "a checkpoint that could not persist the pending work must not drop the records covering it");
    }

    @Test
    public void test_shutdown_keeps_the_log_of_a_collection_with_pending_ids() {
        save("pending");
        recorder.clear();

        fs.checkpointWriteAheadLogs();

        assertFalse(recorder.forcedNames().contains(LOG_REWRITE), recorder.forcedNames().toString());
        assertFalse(recorder.forcedNames().contains(DIRTY_MARKER), recorder.forcedNames().toString());
    }

    @Test
    public void test_the_drain_deletes_a_marker_a_checkpoint_wrote() throws Exception {
        checkpointEveryWrite();
        save("pending");
        assertTrue(TestUtils.dirtyIndexCollections().contains(TestGlobals.DB + "|" + TestGlobals.COLL));

        pendingIndexWrites.clear(TestGlobals.DB, TestGlobals.COLL, "pending", Long.MAX_VALUE);

        assertFalse(TestUtils.dirtyIndexCollections().contains(TestGlobals.DB + "|" + TestGlobals.COLL));
    }

    @Test
    public void test_a_second_instance_does_not_take_over_the_guard() throws Exception {
        assertTrue(new PendingIndexWrites().idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
        checkpointEveryWrite();

        save("pending");

        assertTrue(recorder.forcedNames().contains(DIRTY_MARKER),
                "the checkpoint must ask the published singleton, which holds the pending id, not the latest instance");
    }
}
