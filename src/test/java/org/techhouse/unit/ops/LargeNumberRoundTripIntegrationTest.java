package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

// Numbers outside the int/long ranges used to be clamped on the way in and out of a document; this
// exercises the whole SAVE -> FIND_BY_ID -> AGGREGATE -> REINDEX path with them.
public class LargeNumberRoundTripIntegrationTest {
    private static final String COLL = "largeNumberColl";
    private static final String INDEXED_COLL = "largeNumberIndexedColl";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

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

    private void save(String coll, String id, double amount) {
        final var request = new SaveRequest(TestGlobals.DB, coll);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("amount", new JsonNumber(amount));
        request.setObject(object);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    // A saved document keeps its out-of-range numbers through a positioned read
    @Test
    public void test_save_and_find_by_id_preserve_large_numbers() {
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLL));
        save(COLL, "big", 1e20);
        save(COLL, "huge", 9.223372036854776E18);
        save(COLL, "tiny", 1e-7);
        save(COLL, "small", 42);

        assertEquals(1e20, amountOf("big"));
        assertEquals(9.223372036854776E18, amountOf("huge"));
        assertEquals(1e-7, amountOf("tiny"));
        assertEquals(42d, amountOf("small"));
    }

    // The same values match through an index-backed aggregation, before and after a reindex
    @Test
    public void test_aggregate_and_reindex_on_large_numbers() {
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, INDEXED_COLL));
        save(INDEXED_COLL, "agg1", 1e20);
        save(INDEXED_COLL, "agg2", 5);
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, INDEXED_COLL, "amount"));

        assertEquals(1, matches(1e20));
        assertEquals(1, matches(5));

        final var reindex = new ReindexRequest(TestGlobals.DB, INDEXED_COLL, List.of("amount"));
        assertEquals(OperationStatus.OK, processor.processMessage(reindex).getStatus());

        assertEquals(1, matches(1e20));
        assertEquals(1, matches(5));
    }

    private double amountOf(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, COLL);
        request.set_id(id);
        final var response = (FindByIdResponse) processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());
        return response.getObject().get("amount").asJsonNumber().getValue().doubleValue();
    }

    private int matches(double amount) {
        final var request = new AggregateRequest(TestGlobals.DB, INDEXED_COLL);
        request.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "amount", new JsonNumber(amount)))));
        final var response = (AggregateResponse) processor.processMessage(request);
        assertTrue(response.getStatus() == OperationStatus.OK || response.getStatus() == OperationStatus.NOT_FOUND);
        return response.getResults() == null ? 0 : response.getResults().size();
    }
}
