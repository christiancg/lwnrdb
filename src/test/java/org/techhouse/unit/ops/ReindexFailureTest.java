package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

public class ReindexFailureTest {
    private static final String FIELD = "status";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        seed("s0", new JsonString("active"));
        seed("s1", new JsonString("active"));
        seed("n0", new JsonNumber(7));
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, FIELD));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
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
        assertEquals(2, fieldIndexFiles().length);
        blockStringIndexRewrite();

        final var response = processor
                .processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of(FIELD)));

        assertNotEquals(OperationStatus.OK, response.getStatus());
        assertEquals(0, fieldIndexFiles().length, "a failed build must not leave some of the field's type files");
        assertEquals(2, countActive(), "a registered field with no index files must answer by scan");
    }
}
