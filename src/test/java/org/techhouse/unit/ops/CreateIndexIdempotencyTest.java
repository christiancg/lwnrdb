package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CreateIndexIdempotencyTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        seed("i0", "active");
        seed("i1", "active");
        seed("i2", "archived");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void seed(String id, String status) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("status", status);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        processor.processMessage(request);
    }

    private void createIndex() {
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "status"));
    }

    private int countMatching(FieldOperatorType type) {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(type, "status", new org.techhouse.ejson.elements.JsonString("active")))));
        final var response = (AggregateResponse) processor.processMessage(request);
        return response.getResults() == null ? 0 : response.getResults().size();
    }

    private File indexFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-status-String.idx");
    }

    @Test
    public void test_second_create_index_returns_ok_without_rebuilding() {
        createIndex();
        final var second = processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "status"));
        assertEquals(OperationStatus.OK, second.getStatus());
        assertTrue(second.getMessage().contains("already exists"));
    }

    @Test
    public void test_repeated_create_index_does_not_duplicate_index_lines() throws Exception {
        createIndex();
        final var afterFirst = Files.readAllLines(indexFile().toPath()).stream().filter(l -> !l.isBlank()).count();
        createIndex();
        createIndex();
        final var afterThird = Files.readAllLines(indexFile().toPath()).stream().filter(l -> !l.isBlank()).count();
        assertEquals(afterFirst, afterThird);
    }

    @Test
    public void test_repeated_create_index_keeps_not_equals_correct() {
        assertEquals(1, countMatching(FieldOperatorType.NOT_EQUALS));
        createIndex();
        assertEquals(1, countMatching(FieldOperatorType.NOT_EQUALS));
        createIndex();
        assertEquals(1, countMatching(FieldOperatorType.NOT_EQUALS));
    }

    @Test
    public void test_repeated_create_index_keeps_equals_correct() {
        createIndex();
        createIndex();
        assertEquals(2, countMatching(FieldOperatorType.EQUALS));
    }
}
