package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CollectionReadinessGuard;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.index.IndexOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CollectionReadinessGuardTest {
    private static final String UNREGISTERED = "neverCreated";
    private static final String ABSENT_COLL = "never_created";
    private static final String FIELD = "email";
    private static final String MISSING = "missing";
    private final Configuration config = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private boolean origEnabled;
    private UUID txClientId;

    @BeforeEach
    public void setUp() throws Exception {
        origEnabled = config.isClusterEnabled();
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(doc("present"));
        save.set_id("present");
        processor.processMessage(save);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (txClientId != null) {
            processor.processMessage(new RollbackTransactionRequest(), txClientId);
            clientTracker.removeById(txClientId);
        }
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        return object;
    }

    static Stream<Arguments> guardCases() {
        final var db = TestGlobals.DB;
        final var coll = TestGlobals.COLL;
        final var runs = Globals.SCRIPT_RUNS_COLLECTION_NAME;
        final var users = Globals.ADMIN_USERS_COLLECTION_NAME;
        final var notFound = ErrorCode.COLLECTION_NOT_FOUND;
        final var notReady = ErrorCode.COLLECTION_NOT_READY;
        return Stream.of(
                Arguments.of("test_a_known_collection_is_allowed_through", false, false, OperationType.SAVE, db,
                        List.of(coll), null),
                Arguments.of("test_an_unknown_collection_is_a_definitive_not_found_without_clustering", false, false,
                        OperationType.SAVE, db, List.of(MISSING), notFound),
                Arguments.of("test_an_unknown_collection_is_retryable_when_clustered", true, false,
                        OperationType.BULK_SAVE, db, List.of(MISSING), notReady),
                Arguments.of("test_the_refusal_carries_the_requested_operation_type", false, false,
                        OperationType.DELETE, db, List.of(MISSING), notFound),
                Arguments.of("test_an_unknown_database_is_refused_too", false, false, OperationType.SAVE, "no-such-db",
                        List.of(coll), notFound),
                Arguments.of("test_read_of_registered_collection_passes", false, true, OperationType.AGGREGATE, db,
                        List.of(coll), null),
                Arguments.of("test_read_of_unregistered_collection_is_not_found", false, true, OperationType.FIND_BY_ID,
                        db, List.of(MISSING), notFound),
                Arguments.of("test_clustered_read_of_unregistered_collection_is_not_ready", true, true,
                        OperationType.LISTEN, db, List.of(MISSING), notReady),
                Arguments.of("test_join_target_unregistered_is_refused", false, true, OperationType.AGGREGATE, db,
                        List.of(coll, MISSING), notFound),
                Arguments.of("test_mis_cased_collection_read_is_refused", false, true, OperationType.FIND_BY_ID, db,
                        List.of(coll.toUpperCase(Locale.ROOT)), notFound),
                Arguments.of("test_admin_database_read_passes", false, true, OperationType.AGGREGATE,
                        Globals.ADMIN_DB_NAME, List.of(users), null),
                Arguments.of("test_admin_database_read_passes", false, true, OperationType.AGGREGATE,
                        Globals.ADMIN_PAGES_DB_NAME, List.of("anything"), null),
                Arguments.of("test_mis_cased_admin_database_read_is_refused", false, true, OperationType.AGGREGATE,
                        Globals.ADMIN_DB_NAME.toUpperCase(Locale.ROOT), List.of(users), notFound),
                Arguments.of("test_unwritten_script_runs_read_passes", false, true, OperationType.AGGREGATE, db,
                        List.of(runs), null),
                Arguments.of("test_mis_cased_script_runs_read_is_refused", false, true, OperationType.AGGREGATE, db,
                        List.of(runs.toUpperCase(Locale.ROOT)), notFound));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("guardCases")
    public void test_the_readiness_guard(String scenario, boolean clustered, boolean read, OperationType type,
            String dbName, List<String> collNames, ErrorCode expected) throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", clustered);

        final var response = read
                ? CollectionReadinessGuard.checkRead(type, dbName, collNames)
                : CollectionReadinessGuard.check(type, dbName, collNames.getFirst());

        if (expected == null) {
            assertNull(response, scenario);
            return;
        }
        assertNotNull(response, scenario);
        assertEquals(expected.getCode(), response.getErrorCode(), scenario);
        assertEquals(expected.getStatus(), response.getStatus(), scenario);
        assertEquals(type, response.getType(), scenario);
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

    private static CreateIndexRequest createIndexOn(String collName) {
        return new CreateIndexRequest(TestGlobals.DB, collName, FIELD);
    }

    @Test
    public void create_index_on_missing_collection_answers_not_found() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);

        final var response = processor.processMessage(createIndexOn(ABSENT_COLL));

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void create_index_on_missing_collection_answers_not_ready_when_clustered() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);

        final var response = IndexOperationHelper.processCreateIndex(createIndexOn(ABSENT_COLL));

        assertEquals(ErrorCode.COLLECTION_NOT_READY.getCode(), response.getErrorCode());
    }

    @Test
    public void create_index_on_missing_collection_leaves_nothing_behind() {
        processor.processMessage(createIndexOn(ABSENT_COLL));

        assertFalse(new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + ABSENT_COLL)
                .exists());
        final var created = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, ABSENT_COLL));
        assertEquals(OperationStatus.OK, created.getStatus());
        assertTrue(cache.hasNoIndex(TestGlobals.DB, ABSENT_COLL, FIELD));
    }

    @Test
    public void create_index_on_an_existing_collection_still_succeeds() {
        final var response = processor.processMessage(createIndexOn(TestGlobals.COLL));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(cache.hasNoIndex(TestGlobals.DB, TestGlobals.COLL, FIELD));
    }

    private void startBufferingTransaction() {
        txClientId = clientTracker.registerForwardedClient("buffer-readiness");
        processor.processMessage(new StartTransactionRequest(), txClientId);
    }

    private OperationResponse bufferSave(String collName) {
        final var request = new SaveRequest(TestGlobals.DB, collName);
        request.setObject(doc("buffered"));
        return processor.processMessage(request, txClientId);
    }

    @Test
    public void test_buffer_save_refuses_a_missing_collection_at_buffer_time() {
        startBufferingTransaction();

        final var response = bufferSave(ABSENT_COLL);

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode(),
                "the buffered path answers what the standalone path answers, not a commit-time failure");
    }

    @Test
    public void test_buffer_bulk_save_refuses_a_missing_collection_at_buffer_time() {
        startBufferingTransaction();
        final var request = new BulkSaveRequest(TestGlobals.DB, ABSENT_COLL);
        request.setObjects(List.of(doc("bulk-buffered")));

        final var response = processor.processMessage(request, txClientId);

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_buffer_delete_refuses_a_missing_collection_at_buffer_time() {
        startBufferingTransaction();
        final var request = new DeleteRequest(TestGlobals.DB, ABSENT_COLL);
        request.set_id("gone");

        final var response = processor.processMessage(request, txClientId);

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_a_second_op_on_a_live_collection_still_succeeds() {
        startBufferingTransaction();
        assertNull(bufferSave(TestGlobals.COLL).getErrorCode());
        final var second = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        second.setObject(doc("buffered-2"));

        final var response = processor.processMessage(second, txClientId);

        assertNotEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode(),
                "the lock is already held, so the reordered acquisition is a no-op for later ops");
    }
}
