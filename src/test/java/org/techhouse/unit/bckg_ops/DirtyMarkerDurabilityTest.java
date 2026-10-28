package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.IndexLogChange;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DirtyMarkerDurabilityTest {
    private static final String DIRTY_MARKER = "-indexes.dirty";
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
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
        recorder.close();
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationStatus saveLogged() {
        final var object = new JsonObject();
        object.addProperty("_id", "logged");
        object.addProperty("name", "x");
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(object);
        return processor.processMessage(save).getStatus();
    }

    @Test
    public void test_a_save_forces_no_marker() {
        assertEquals(OperationStatus.OK, saveLogged());

        final var forced = recorder.forcedNames();
        assertTrue(forced.contains(TestGlobals.COLL + ".wal"), forced.toString());
        assertFalse(forced.stream().anyMatch(name -> name.endsWith(DIRTY_MARKER)), forced.toString());
    }

    @Test
    public void test_a_save_succeeds_when_the_marker_cannot_be_forced() {
        recorder.failingFor(DIRTY_MARKER);

        assertEquals(OperationStatus.OK, saveLogged());

        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("logged");
        assertEquals(OperationStatus.OK, processor.processMessage(find).getStatus());
    }

    @Test
    public void test_startup_recovery_keeps_its_marks_when_the_marker_cannot_be_forced() {
        recorder.failingFor(DIRTY_MARKER);

        pendingIndexWrites.markRecovered(TestGlobals.DB, TestGlobals.COLL, List.of("adopted"));

        assertEquals(Set.of("adopted"), pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL),
                "an adopted or recovered id must stay pending in memory even when its marker is not durable");
    }

    @Test
    public void test_recovered_marks_still_write_the_marker() {
        pendingIndexWrites.markRecovered(TestGlobals.DB, TestGlobals.COLL, List.of("adopted"));

        assertTrue(recorder.forcedNames().stream().anyMatch(name -> name.endsWith(DIRTY_MARKER)),
                "work no log record covers needs the marker to survive a crash");
    }

    private File markerFile() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + DIRTY_MARKER);
    }

    private long recoverWithAnUnforcedIndexLog() throws Exception {
        final var generation = pendingIndexWrites.markRecovered(TestGlobals.DB, TestGlobals.COLL, List.of("adopted"));
        IocContainer.get(FileSystem.class).appendIndexLog(TestGlobals.DB, TestGlobals.COLL, "name", "String",
                List.of(new IndexLogChange(FieldIndexEntry.fileKeyOf("x"), List.of("adopted"), List.of())),
                List.of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "x",
                        new HashSet<>(Set.of("adopted")))));
        assertTrue(markerFile().exists());
        recorder.clear();
        return generation;
    }

    @Test
    public void test_clearing_the_marker_forces_the_unforced_index_logs_first() throws Exception {
        final var generation = recoverWithAnUnforcedIndexLog();

        pendingIndexWrites.clear(TestGlobals.DB, TestGlobals.COLL, List.of("adopted"), generation);

        assertTrue(recorder.forcedNames().stream().anyMatch(name -> name.endsWith(".idxlog")),
                recorder.forcedNames().toString());
        assertFalse(markerFile().exists());
    }

    @Test
    public void test_a_marker_whose_index_logs_cannot_be_forced_is_kept() throws Exception {
        final var generation = recoverWithAnUnforcedIndexLog();
        recorder.failingFor(".idxlog");

        pendingIndexWrites.clear(TestGlobals.DB, TestGlobals.COLL, List.of("adopted"), generation);

        assertTrue(markerFile().exists(), "the marker still covers index work that is not durable");
    }
}
