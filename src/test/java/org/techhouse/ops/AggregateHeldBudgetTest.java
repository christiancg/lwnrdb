package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregateHeldBudgetTest {
    private static final int DOCUMENTS = 200;
    private static final long POOL_BYTES = 10_000L;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final AggregateHeldBudget pool = IocContainer.get(AggregateHeldBudget.class);
    private final Configuration configuration = Configuration.getInstance();
    private long baseline;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var documents = new StringBuilder(DOCUMENTS * 256);
        for (var i = 0; i < DOCUMENTS; i++) {
            documents.append(i == 0 ? "" : ",").append("{\"_id\":\"d").append(i).append("\",\"k\":\"g").append(i % 4)
                    .append("\",\"pad\":\"").repeat("x", 200).append("\"}");
        }
        final var saved = run("{\"type\":\"BULK_SAVE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"objects\":[" + documents + "]}");
        assertEquals(OperationStatus.OK, saved.getStatus(), saved.getMessage());
        baseline = pool.reservedBytes();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "aggregateTotalHeldBytes",
                ConfigKey.AGGREGATE_TOTAL_HELD_BYTES.defaultValue());
        TestUtils.setPrivateField(configuration, "aggregateMaxResultBytes",
                ConfigKey.AGGREGATE_MAX_RESULT_BYTES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse run(String json) {
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    private OperationResponse groupBy() {
        return run("{\"type\":\"AGGREGATE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"aggregationSteps\":[{\"type\":\"GROUP_BY\",\"fieldName\":\"k\"}]}");
    }

    private static JsonObject deferredRow() {
        final var row = new JsonObject();
        row.add("_id", new JsonString("deferred"));
        row.add("pad", new JsonString("y".repeat(100)));
        return row;
    }

    @Test
    public void aRunPastTheSharedPoolAnswers503_20() throws Exception {
        TestUtils.setPrivateField(configuration, "aggregateTotalHeldBytes", String.valueOf(POOL_BYTES));

        assertEquals(ErrorCode.AGGREGATE_BUDGET_EXHAUSTED.getCode(), groupBy().getErrorCode());
        assertEquals(baseline, pool.reservedBytes(), "a refused run gives back what it reserved");
    }

    @Test
    public void thePerRequestCapStillAnswers400_36First() throws Exception {
        TestUtils.setPrivateField(configuration, "aggregateMaxResultBytes", String.valueOf(POOL_BYTES));
        TestUtils.setPrivateField(configuration, "aggregateTotalHeldBytes", String.valueOf(POOL_BYTES * 2));

        assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE.getCode(), groupBy().getErrorCode());
        assertEquals(baseline, pool.reservedBytes());
    }

    @Test
    public void anAnsweredRunReleasesEverythingItReserved() {
        assertEquals(OperationStatus.OK, groupBy().getStatus());
        assertEquals(baseline, pool.reservedBytes());
    }

    @Test
    public void twoConcurrentRunsShareThePool() throws Exception {
        TestUtils.setPrivateField(configuration, "aggregateTotalHeldBytes", String.valueOf(baseline + POOL_BYTES));
        final var first = PipelineContext.of(0L, Long.MAX_VALUE, null);
        final var second = PipelineContext.of(0L, Long.MAX_VALUE, null);
        first.chargeHeld(POOL_BYTES - 100L);

        final var refused = assertThrows(AggregateLimitException.class, () -> second.chargeHeld(200L));
        assertEquals(ErrorCode.AGGREGATE_BUDGET_EXHAUSTED, refused.getErrorCode());
        first.close();
        second.chargeHeld(200L);
        second.close();

        assertEquals(baseline, pool.reservedBytes());
    }

    @Test
    public void releasedHeldBytesGoBackToThePool() {
        final var context = PipelineContext.of(0L, Long.MAX_VALUE, null);
        context.chargeHeld(500L);
        context.releaseHeld(300L);

        assertEquals(baseline + 200L, pool.reservedBytes());
        context.close();
        assertEquals(baseline, pool.reservedBytes());
    }

    @Test
    public void deferredHeldRowsAreReservedUntilTheRunCloses() {
        final var context = PipelineContext.of(0L, Long.MAX_VALUE, null);
        final var held = new HeldRows(context);
        held.hold(deferredRow());

        assertTrue(pool.reservedBytes() - baseline >= HeldRows.bytesAtMost(deferredRow()));
        context.close();
        assertEquals(baseline, pool.reservedBytes());
    }

    @Test
    public void aRunWithoutScriptsNeverReserves() {
        final var context = PipelineContext.withoutScripts();
        context.chargeHeld(1_000_000L);

        assertEquals(baseline, pool.reservedBytes());
        context.close();
    }
}
