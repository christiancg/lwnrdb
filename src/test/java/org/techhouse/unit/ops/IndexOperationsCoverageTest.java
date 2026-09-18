package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexOperationsCoverageTest {
    private static final String LOCKED_FIELD = "s";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    private void seed(String id, String s, int n) {
        final var obj = new JsonObject();
        obj.addProperty("_id", id);
        obj.addProperty("s", s);
        obj.addProperty("n", n);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(obj);
        processor.processMessage(request);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        seed("d1", "a", 10);
        seed("d2", "b", 20);
        seed("d3", "a", 30);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_create_index_then_index_backed_filter() {
        final var created = processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "s"));
        assertEquals(OperationStatus.OK, created.getStatus());

        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List
                .of(new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "s", new JsonString("a")))));
        final var response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());
    }

    @Test
    public void test_create_numeric_index_reindex_and_drop() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "n")).getStatus());

        final var reindex = processor
                .processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of("n")));
        assertEquals(OperationType.REINDEX, reindex.getType());
        assertEquals(OperationStatus.OK, reindex.getStatus());

        final var dropped = processor.processMessage(new DropIndexRequest(TestGlobals.DB, TestGlobals.COLL, "n"));
        assertEquals(OperationStatus.OK, dropped.getStatus());
    }

    @Test
    public void test_index_backed_range_filter() {
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "n"));
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.GREATER_THAN, "n", new JsonNumber(15)))));
        assertEquals(2,
                ((org.techhouse.ops.resp.AggregateResponse) processor.processMessage(request)).getResults().size());
    }

    private static void assertBlocksOnTheFieldLock(Runnable indexOperation) throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        locks.lockIndex(TestGlobals.DB, TestGlobals.COLL, LOCKED_FIELD);
        final var finished = new CountDownLatch(1);
        final var worker = new Thread(() -> {
            indexOperation.run();
            finished.countDown();
        }, "index-ddl");
        worker.start();
        try {
            assertFalse(finished.await(300, TimeUnit.MILLISECONDS),
                    "index DDL must hold the field lock across the file writes and the eviction, or a dirty read"
                            + " repopulates the cache from a half-built index");
        } finally {
            locks.releaseIndex(TestGlobals.DB, TestGlobals.COLL, LOCKED_FIELD);
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS), "it must proceed once the field lock is free");
        worker.join(5_000L);
    }

    private static void quietly(IndexDdl operation) {
        try {
            operation.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private interface IndexDdl {
        void run() throws InterruptedException;
    }

    @Test
    public void test_create_index_holds_the_field_lock_through_the_eviction() throws Exception {
        seed("lock1", "a", 1);
        assertBlocksOnTheFieldLock(
                () -> quietly(() -> IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, LOCKED_FIELD)));
    }

    @Test
    public void test_drop_index_holds_the_field_lock_through_the_eviction() throws Exception {
        seed("lock2", "a", 1);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, LOCKED_FIELD);
        assertBlocksOnTheFieldLock(
                () -> quietly(() -> IndexHelper.dropIndex(TestGlobals.DB, TestGlobals.COLL, LOCKED_FIELD)));
    }
}
