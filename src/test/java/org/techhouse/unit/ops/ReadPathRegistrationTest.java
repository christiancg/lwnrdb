package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReadPathRegistrationTest {
    private static final String UNREGISTERED = "neverCreated";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString("present"));
        save.setObject(object);
        save.set_id("present");
        processor.processMessage(save);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse findById(String coll, String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, coll);
        request.set_id(id);
        return processor.processMessage(request);
    }

    private static AggregateRequest count(String coll) {
        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAggregationSteps(List.of(new CountAggregationStep()));
        return request;
    }

    private boolean unregisteredPkIndexCached() throws Exception {
        final var pkIndexes = TestUtils.getPrivateField(cache.userCache(), "pkIndexMap", Map.class);
        return pkIndexes.containsKey(Cache.getCollectionIdentifier(TestGlobals.DB, UNREGISTERED));
    }

    @Test
    public void test_find_by_id_in_unregistered_collection_is_not_found() throws Exception {
        final var response = findById(UNREGISTERED, "present");

        assertEquals("404-11", response.getErrorCode());
        assertFalse(unregisteredPkIndexCached(), "a refused read must not publish anything under that name");
    }

    @Test
    public void test_aggregate_over_unregistered_collection_is_not_found() {
        assertEquals("404-11", processor.processMessage(count(UNREGISTERED)).getErrorCode());
    }

    @Test
    public void test_aggregate_joining_an_unregistered_collection_is_not_found() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new JoinAggregationStep(UNREGISTERED, "_id", "_id", "joined")));

        assertEquals("404-11", processor.processMessage(request).getErrorCode());
    }

    @Test
    public void test_listen_on_unregistered_collection_is_not_found() {
        final var request = new ListenRequest(TestGlobals.DB, UNREGISTERED);
        request.setAggregationSteps(List.of(new CountAggregationStep()));

        assertEquals("404-11", processor.processMessage(request, null).getErrorCode());
    }

    @Test
    public void test_mis_cased_name_of_a_registered_collection_is_not_found() {
        assertEquals("404-11", findById(TestGlobals.COLL.toUpperCase(Locale.ROOT), "present").getErrorCode());
        assertEquals(OperationStatus.OK, findById(TestGlobals.COLL, "present").getStatus());
    }

    @Test
    public void test_refused_analyze_leaves_no_analyze_context() {
        final var request = count(UNREGISTERED);
        request.setAnalyze(true);

        assertEquals("404-11", processor.processMessage(request).getErrorCode());
        assertNull(AnalyzeContext.current(), "a refused analyze must clear its context");
    }

    @Test
    public void test_refused_read_inside_a_transaction_leaves_it_usable() {
        final var clientId = clientTracker.registerForwardedClient("admin");
        TransactionOperationHelper.start(clientId);
        final var request = new FindByIdRequest(TestGlobals.DB, UNREGISTERED);
        request.set_id("present");

        assertEquals("404-11", processor.processMessage(request, clientId).getErrorCode());

        final var transaction = clientTracker.getActiveTransaction(clientId);
        assertNotNull(transaction, "the refusal must not end the transaction");
        assertFalse(transaction.isAborted(), "the refusal must not abort the transaction");
        TransactionOperationHelper.rollback(clientId);
    }

    @Test
    public void test_unwritten_script_runs_stays_readable() {
        assertNotEquals("404-11", processor.processMessage(count(Globals.SCRIPT_RUNS_COLLECTION_NAME)).getErrorCode());
        assertEquals("404-2", findById(Globals.SCRIPT_RUNS_COLLECTION_NAME, "nope").getErrorCode());
    }
}
