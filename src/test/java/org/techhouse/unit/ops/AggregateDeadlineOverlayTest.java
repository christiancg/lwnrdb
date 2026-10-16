package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.admin.ReadPathHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregateDeadlineOverlayTest {
    private static final int DOCUMENTS = 20_000;

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration configuration = Configuration.getInstance();
    private UUID clientId;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var document = new JsonObject();
            document.addProperty("_id", "doc-" + i);
            document.addProperty("n", i);
            objects.add(document);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(objects);
        assertEquals(OperationStatus.OK, IocContainer.get(OperationProcessor.class).processMessage(bulk).getStatus());
        clientId = clientTracker.registerForwardedClient("admin");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TransactionOperationHelper.rollback(clientId);
        clientTracker.removeById(clientId);
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", ConfigKey.AGGREGATE_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void aFilterOverATransactionOverlayStopsAtTheDeadline() throws Exception {
        TransactionOperationHelper.start(clientId, UUID.randomUUID(), 0);
        final var buffered = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var document = new JsonObject();
        document.addProperty("_id", "buffered");
        document.addProperty("n", -1);
        buffered.setObject(document);
        buffered.set_id("buffered");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        assertEquals(OperationStatus.OK, TransactionOperationHelper.bufferSave(buffered, transaction).getStatus());
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", 1);
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "missing", new JsonNumber(1)))));

        final var response = ReadPathHelper.processAggregateOperation(request, transaction);

        assertEquals("408-6", response.getErrorCode());
    }
}
