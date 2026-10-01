package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReindexPendingWritesTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        saveIndexableDocument();
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "name"));
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "label"));
    }

    @AfterEach
    public void tearDown() throws Exception {
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void saveIndexableDocument() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString("doc-1"));
        object.add("name", new JsonString("alpha"));
        object.add("label", new JsonString("first"));
        request.setObject(object);
        processor.processMessage(request);
    }

    private boolean markerPresent() {
        return fs.listDirtyIndexCollections().stream().anyMatch(entry -> entry.contains(TestGlobals.COLL));
    }

    @Test
    public void test_a_full_reindex_clears_the_overlay_and_the_marker() {
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "stranded");
        assertTrue(markerPresent());

        final var response = processor.processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of()));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "a full rebuild read the authoritative documents, so the marks it inherited are redundant");
        assertFalse(markerPresent());
    }

    @Test
    public void test_a_partial_reindex_leaves_the_overlay_and_the_marker() {
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "stranded");
        final var request = new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of("name"));

        final var response = processor.processMessage(request);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("stranded"),
                "the fields it skipped were never re-derived, so the marks still matter");
        assertTrue(markerPresent());
    }

    @Test
    public void test_an_event_queued_before_a_full_reindex_does_not_consume_a_later_writes_mark() {
        final var queuedGeneration = pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "doc-1");
        processor.processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of()));
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "doc-1");

        pendingIndexWrites.clear(TestGlobals.DB, TestGlobals.COLL, "doc-1", queuedGeneration);

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("doc-1"),
                "the newer write's index update has not run yet, so its id must stay reconciled");
        assertTrue(markerPresent());
    }
}
