package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReindexPendingWritesTest {
    private static final String FIELD = "status";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    private void seedIndexedNameAndLabel() {
        saveIndexableDocument();
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "name"));
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "label"));
    }

    private void seedStatuses() {
        seed("s0", new JsonString("active"));
        seed("s1", new JsonString("active"));
        seed("n0", new JsonNumber(7));
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, FIELD));
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
        return TestUtils.dirtyIndexCollections().stream().anyMatch(entry -> entry.contains(TestGlobals.COLL));
    }

    @Test
    public void test_a_full_reindex_clears_the_overlay_and_the_marker() throws IOException {
        seedIndexedNameAndLabel();
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "stranded");
        assertTrue(markerPresent());

        final var response = processor.processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of()));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "a full rebuild read the authoritative documents, so the marks it inherited are redundant");
        assertFalse(markerPresent());
    }

    @Test
    public void test_a_partial_reindex_leaves_the_overlay_and_the_marker() throws IOException {
        seedIndexedNameAndLabel();
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "stranded");
        final var request = new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of("name"));

        final var response = processor.processMessage(request);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("stranded"),
                "the fields it skipped were never re-derived, so the marks still matter");
        assertTrue(markerPresent());
    }

    @Test
    public void test_an_event_queued_before_a_full_reindex_does_not_consume_a_later_writes_mark() throws IOException {
        seedIndexedNameAndLabel();
        final var queuedGeneration = pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "doc-1");
        processor.processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of()));
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "doc-1");

        pendingIndexWrites.clear(TestGlobals.DB, TestGlobals.COLL, "doc-1", queuedGeneration);

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("doc-1"),
                "the newer write's index update has not run yet, so its id must stay reconciled");
        assertTrue(markerPresent());
    }

    private void seed(String id, JsonBaseElement value) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.add(FIELD, value);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        processor.processMessage(request);
    }

    private static File collectionFolder() {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
    }

    private static File[] fieldIndexFiles() {
        final var prefix = TestGlobals.COLL + "-" + FIELD + "-";
        final var files = collectionFolder().listFiles((_, name) -> name.startsWith(prefix) && name.endsWith(".idx"));
        return files == null ? new File[0] : files;
    }

    private static void blockStringIndexRewrite() throws Exception {
        final var tempTarget = new File(collectionFolder(), TestGlobals.COLL + "-" + FIELD + "-String.idx.repair");
        assertTrue(tempTarget.mkdir());
        Files.writeString(new File(tempTarget, "occupied").toPath(), "x");
    }

    private int countActive() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, FIELD, new JsonString("active")))));
        final var response = (AggregateResponse) processor.processMessage(request);
        return response.getResults() == null ? 0 : response.getResults().size();
    }

    @Test
    public void test_a_failed_reindex_leaves_no_partial_index_and_answers_by_scan() throws Exception {
        seedStatuses();
        assertEquals(2, fieldIndexFiles().length);
        blockStringIndexRewrite();

        final var response = processor
                .processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of(FIELD)));

        assertNotEquals(OperationStatus.OK, response.getStatus());
        assertEquals(0, fieldIndexFiles().length, "a failed build must not leave some of the field's type files");
        assertEquals(2, countActive(), "a registered field with no index files must answer by scan");
    }
}
