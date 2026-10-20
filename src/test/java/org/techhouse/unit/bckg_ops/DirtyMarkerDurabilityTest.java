package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.ejson.elements.JsonObject;
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
}
