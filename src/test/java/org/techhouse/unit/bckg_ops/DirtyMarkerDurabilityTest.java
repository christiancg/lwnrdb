package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
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
        recorder = RecordingForcer.install(true);
        recorder.failingFor(DIRTY_MARKER);
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_a_marker_that_cannot_be_forced_fails_the_mark_and_leaves_nothing_pending() {
        assertThrows(IOException.class, () -> pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "late"));

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_startup_recovery_keeps_its_marks_when_the_marker_cannot_be_forced() {
        pendingIndexWrites.markRecovered(TestGlobals.DB, TestGlobals.COLL, List.of("adopted"));

        assertEquals(Set.of("adopted"), pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL),
                "an adopted or recovered id must stay pending in memory even when its marker is not durable");
    }

    @Test
    public void test_a_save_whose_marker_cannot_be_forced_is_refused_and_writes_nothing() {
        final var object = new JsonObject();
        object.addProperty("_id", "unmarked");
        object.addProperty("name", "x");
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(object);

        assertNotEquals(OperationStatus.OK, processor.processMessage(save).getStatus());

        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("unmarked");
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), processor.processMessage(find).getErrorCode());
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }
}
