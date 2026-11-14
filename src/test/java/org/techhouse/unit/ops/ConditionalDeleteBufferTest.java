package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.DeleteManyResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class ConditionalDeleteBufferTest {
    private static final String WRITER = "tx_conditional_writer";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private UUID clientId;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(WRITER, "h", false, Set.of(), new HashMap<>(),
                new HashMap<>(Map.of(TestGlobals.DB + "|" + TestGlobals.COLL, PermissionLevel.READ_WRITE))));
        for (var i = 0; i < 6; i++) {
            assertEquals(OperationStatus.OK,
                    processor.processMessage(RequestParser.parseRequest(save("c" + i, i))).getStatus());
        }
        clientId = clientTracker.registerForwardedClient(WRITER);
        TransactionOperationHelper.start(clientId);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientTracker.getActiveTransaction(clientId) != null) {
            TransactionOperationHelper.abort(clientId);
        }
        clientTracker.removeById(clientId);
        TestUtils.setPrivateField(configuration, "transactionMaxOps", ConfigKey.TRANSACTION_MAX_OPS.defaultValue());
        TestUtils.setPrivateField(configuration, "deleteMaxMatches", ConfigKey.DELETE_MAX_MATCHES.defaultValue());
        TriggerTestSupport.clearTriggersAndProcedures();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String save(String id, int n) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"" + id + "\",\"n\":" + n + "}}";
    }

    private static String deleteWhereNBelow(int bound) {
        return "{\"type\":\"DELETE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"condition\":{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"n\","
                + "\"value\":" + bound + "}}";
    }

    private static String deleteFirstSeeded() {
        return "{\"type\":\"DELETE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"_id\":\"c0\"}";
    }

    private OperationResponse inTransaction(String json) {
        return processor.processMessage(RequestParser.parseRequest(json), clientId);
    }

    private Set<String> committedIds() {
        final var response = processor.processMessage(
                RequestParser.parseRequest("{\"type\":\"AGGREGATE\"," + "\"databaseName\":\"" + TestGlobals.DB
                        + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"aggregationSteps\":[]}"));
        final var ids = new TreeSet<String>();
        if (response instanceof AggregateResponse aggregate) {
            aggregate.getResults().forEach(row -> ids.add(row.get(Globals.PK_FIELD).asJsonString().getValue()));
        }
        return ids;
    }

    private static long deletedCount(OperationResponse response) {
        return assertInstanceOf(DeleteManyResponse.class, response, response.getMessage()).getDeletedCount();
    }

    @Test
    public void test_a_condition_sees_the_transactions_own_saves_and_deletes() {
        assertEquals(OperationStatus.OK, inTransaction(save("buffered", 1)).getStatus());
        assertEquals(OperationStatus.OK, inTransaction(deleteFirstSeeded()).getStatus());

        assertEquals(3L, deletedCount(inTransaction(deleteWhereNBelow(3))));

        TransactionOperationHelper.commit(clientId);
        assertEquals(Set.of("c3", "c4", "c5"), committedIds());
    }

    @Test
    public void test_commit_deletes_every_buffered_match() {
        assertEquals(4L, deletedCount(inTransaction(deleteWhereNBelow(4))));
        assertEquals(6, committedIds().size(), "nothing is applied before the commit");

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        assertEquals(Set.of("c4", "c5"), committedIds());
    }

    @Test
    public void test_rollback_keeps_every_document() {
        assertEquals(6L, deletedCount(inTransaction(deleteWhereNBelow(100))));

        TransactionOperationHelper.rollback(clientId);

        assertEquals(6, committedIds().size());
    }

    @Test
    public void test_no_match_buffers_nothing_and_answers_zero() {
        assertEquals(0L, deletedCount(inTransaction(deleteWhereNBelow(-1))));

        assertEquals(0, clientTracker.getActiveTransaction(clientId).getPendingOps().size());
    }

    @Test
    public void test_past_transaction_max_ops_is_400_26_and_buffers_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionMaxOps", 3);

        final var response = inTransaction(deleteWhereNBelow(4));

        assertEquals(ErrorCode.TRANSACTION_TOO_LARGE.getCode(), response.getErrorCode());
        assertEquals(0, clientTracker.getActiveTransaction(clientId).getPendingOps().size());
    }

    @Test
    public void test_the_cap_applies_inside_a_transaction() throws Exception {
        TestUtils.setPrivateField(configuration, "deleteMaxMatches", 2);

        final var response = inTransaction(deleteWhereNBelow(3));

        assertEquals(ErrorCode.DELETE_MATCHES_TOO_MANY.getCode(), response.getErrorCode());
        assertEquals(0, clientTracker.getActiveTransaction(clientId).getPendingOps().size());
    }

    @Test
    public void test_a_hook_rejection_buffers_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "guard",
                "export default (d) => { if (d.n === 2) { throw new Error('keep'); } };"), WRITER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("guard", new LinkedHashSet<>(Set.of(EventType.DELETED)), "guard",
                        TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, true, WRITER, 1L, 1L,
                        1L, WRITER)));

        final var response = inTransaction(deleteWhereNBelow(4));

        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), response.getErrorCode());
        assertEquals(0, clientTracker.getActiveTransaction(clientId).getPendingOps().size());
    }
}
