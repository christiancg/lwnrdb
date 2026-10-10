package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class UncleanStopMarkerTest {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "name"));
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "label"));
    }

    @AfterEach
    public void tearDown() throws Exception {
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static File uncleanStopMarker() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-indexes.unclean");
    }

    private static String identifier() {
        return Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    }

    private List<String> stopUncleanlyAndRestart() {
        fs.markIndexesDirty(TestGlobals.DB, TestGlobals.COLL);
        return fs.retainUncleanStopMarkers();
    }

    private void writeAndDrain(String id) {
        final var generation = pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, id);
        pendingIndexWrites.clear(TestGlobals.DB, TestGlobals.COLL, id, generation);
    }

    @Test
    public void test_startup_retains_the_marker_an_unclean_stop_left_and_reports_it() {
        assertEquals(List.of(identifier()), stopUncleanlyAndRestart());

        assertTrue(uncleanStopMarker().isFile());
        assertFalse(fs.listDirtyIndexCollections().contains(identifier()),
                "the drain owns the dirty marker, so the inherited one must not stay under that name");
    }

    @Test
    public void test_a_write_drained_after_the_restart_does_not_retire_the_unclean_stop() {
        stopUncleanlyAndRestart();

        writeAndDrain("after-restart");

        assertTrue(uncleanStopMarker().isFile(),
                "the ids pending at the crash were never re-derived, so only REINDEX may retire the warning");
    }

    @Test
    public void test_a_second_restart_without_reindex_still_reports_the_collection() {
        stopUncleanlyAndRestart();

        assertEquals(List.of(identifier()), fs.retainUncleanStopMarkers());
    }

    @Test
    public void test_a_partial_reindex_keeps_the_unclean_stop_and_a_full_one_retires_it() {
        stopUncleanlyAndRestart();

        assertEquals(OperationStatus.OK, processor
                .processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of("name"))).getStatus());
        assertTrue(uncleanStopMarker().isFile());

        assertEquals(OperationStatus.OK,
                processor.processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of())).getStatus());
        assertFalse(uncleanStopMarker().exists());
        assertTrue(fs.retainUncleanStopMarkers().isEmpty());
    }

    @Test
    public void test_a_collection_whose_work_drained_before_the_stop_is_not_reported() {
        writeAndDrain("doc-1");

        assertTrue(fs.retainUncleanStopMarkers().isEmpty());
    }
}
