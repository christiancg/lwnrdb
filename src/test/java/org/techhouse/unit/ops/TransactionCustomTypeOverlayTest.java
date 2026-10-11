package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionCustomTypeOverlayTest {
    private static final String WHEN_VALUE = "#datetime(2024-01-01T10:00:00)";
    private static final String WHERE_VALUE = "#geo(0.0,0.0)";

    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void resetClientTracker() throws Exception {
        TestUtils.resetClients();
    }

    private UUID newClient() {
        final var socket = mock(Socket.class);
        final var addr = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private SaveRequest saveWithCustomFields(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("when", new JsonDateTime(WHEN_VALUE));
        document.add("where", new JsonGeo(WHERE_VALUE));
        request.setObject(document);
        request.set_id(id);
        return request;
    }

    private AggregateRequest geoFilterRequest() {
        final var args = new JsonObject();
        args.add("value", new JsonGeo(WHERE_VALUE));
        args.add("comparator", new JsonString("SMALLER_THAN"));
        args.addProperty("distance", 1000);
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new FilterAggregationStep(
                new CustomOperator(JsonGeo.OPERATOR_DISTANCE, "where", new JsonGeo(WHERE_VALUE), args))));
        return request;
    }

    @Test
    public void test_a_buffered_custom_value_is_readable_as_a_custom_type() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        try {
            processor.processMessage(saveWithCustomFields("txn-custom-1"), clientId);

            final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
            find.set_id("txn-custom-1");
            final var response = processor.processMessage(find, clientId);

            assertInstanceOf(FindByIdResponse.class, response);
            final var document = ((FindByIdResponse) response).getObject();
            assertTrue(document.get("when").isJsonCustom());
            assertTrue(document.get("where").isJsonCustom());
            assertEquals(WHEN_VALUE, document.get("when").asJsonString().getValue());
        } finally {
            processor.processMessage(new RollbackTransactionRequest(), clientId);
        }
    }

    @Test
    public void test_a_custom_equality_filter_inside_a_transaction_sees_its_own_write() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        try {
            processor.processMessage(saveWithCustomFields("txn-custom-2"), clientId);

            final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setAggregationSteps(List.of(new FilterAggregationStep(new ConjunctionOperator(
                    ConjunctionOperatorType.AND,
                    List.of(new FieldOperator(FieldOperatorType.EQUALS, "when", new JsonDateTime(WHEN_VALUE)))))));
            final var response = processor.processMessage(request, clientId);

            assertInstanceOf(AggregateResponse.class, response);
            assertEquals(1, ((AggregateResponse) response).getResults().size());
        } finally {
            processor.processMessage(new RollbackTransactionRequest(), clientId);
        }
    }

    @Test
    public void test_a_geo_filter_inside_a_transaction_sees_its_own_write() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        try {
            processor.processMessage(saveWithCustomFields("txn-custom-3"), clientId);

            final var response = processor.processMessage(geoFilterRequest(), clientId);

            assertInstanceOf(AggregateResponse.class, response);
            assertEquals(1, ((AggregateResponse) response).getResults().size());
        } finally {
            processor.processMessage(new RollbackTransactionRequest(), clientId);
        }
    }

    @Test
    public void test_a_committed_custom_value_stays_a_custom_type() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveWithCustomFields("txn-custom-4"), clientId);
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CommitTransactionRequest(), clientId).getStatus());

        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("txn-custom-4");
        final var response = processor.processMessage(find, newClient());

        assertInstanceOf(FindByIdResponse.class, response);
        final var document = ((FindByIdResponse) response).getObject();
        assertTrue(document.get("when").isJsonCustom());
        assertTrue(document.get("where").isJsonCustom());
    }
}
