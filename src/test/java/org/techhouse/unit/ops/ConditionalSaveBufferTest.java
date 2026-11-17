package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveWhereResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class ConditionalSaveBufferTest {
    private static final String WRITER = "tx_conditional_updater";
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
        TestUtils.setPrivateField(configuration, "updateMaxMatches", ConfigKey.UPDATE_MAX_MATCHES.defaultValue());
        TriggerTestSupport.clearTriggersAndProcedures();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String save(String id, int n) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"" + id + "\",\"n\":" + n + "}}";
    }

    private static String saveWhereNBelow(int bound, String status) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"condition\":{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"n\"," + "\"value\":" + bound
                + "},\"object\":{\"status\":\"" + status + "\"}}";
    }

    private static String deleteFirstSeeded() {
        return "{\"type\":\"DELETE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"_id\":\"c0\"}";
    }

    private OperationResponse inTransaction(String json) {
        return processor.processMessage(RequestParser.parseRequest(json), clientId);
    }

    private String committedStatus(String id) {
        final var response = processor.processMessage(
                RequestParser.parseRequest("{\"type\":\"FIND_BY_ID\"," + "\"databaseName\":\"" + TestGlobals.DB
                        + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"" + id + "\"}"));
        final var document = assertInstanceOf(FindByIdResponse.class, response, response.getMessage()).getObject();
        return document.has("status") ? document.get("status").asJsonString().getValue() : null;
    }

    private int committedN(String id) {
        final var response = processor.processMessage(
                RequestParser.parseRequest("{\"type\":\"FIND_BY_ID\"," + "\"databaseName\":\"" + TestGlobals.DB
                        + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"" + id + "\"}"));
        return assertInstanceOf(FindByIdResponse.class, response).getObject().get("n").asJsonNumber().getValue()
                .intValue();
    }

    private static long updatedCount(OperationResponse response) {
        return assertInstanceOf(SaveWhereResponse.class, response, response.getMessage()).getUpdatedCount();
    }

    @Test
    public void test_a_condition_sees_the_transactions_own_saves_and_deletes() {
        assertEquals(OperationStatus.OK, inTransaction(save("buffered", 1)).getStatus());
        assertEquals(OperationStatus.OK, inTransaction(deleteFirstSeeded()).getStatus());

        assertEquals(3L, updatedCount(inTransaction(saveWhereNBelow(3, "seen"))));

        TransactionOperationHelper.commit(clientId);
        assertEquals("seen", committedStatus("buffered"));
        assertEquals("seen", committedStatus("c1"));
        assertEquals("seen", committedStatus("c2"));
        assertNull(committedStatus("c3"));
    }

    @Test
    public void test_commit_applies_every_merge_and_keeps_the_other_fields() {
        assertEquals(4L, updatedCount(inTransaction(saveWhereNBelow(4, "done"))));
        assertNull(committedStatus("c1"), "nothing is applied before the commit");

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        for (var i = 0; i < 6; i++) {
            assertEquals(i < 4 ? "done" : null, committedStatus("c" + i));
            assertEquals(i, committedN("c" + i));
        }
    }

    @Test
    public void test_rollback_changes_nothing() {
        assertEquals(6L, updatedCount(inTransaction(saveWhereNBelow(100, "done"))));

        TransactionOperationHelper.rollback(clientId);

        for (var i = 0; i < 6; i++) {
            assertNull(committedStatus("c" + i));
        }
    }

    @Test
    public void test_no_match_buffers_nothing_and_answers_zero() {
        assertEquals(0L, updatedCount(inTransaction(saveWhereNBelow(-1, "done"))));

        assertEquals(0, clientTracker.getActiveTransaction(clientId).getPendingOps().size());
    }

    @Test
    public void test_past_transaction_max_ops_is_400_26_and_buffers_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionMaxOps", 3);

        final var response = inTransaction(saveWhereNBelow(4, "done"));

        assertEquals(ErrorCode.TRANSACTION_TOO_LARGE.getCode(), response.getErrorCode());
        assertEquals(OperationType.SAVE, response.getType());
        assertEquals(0, clientTracker.getActiveTransaction(clientId).getPendingOps().size());
    }

    @Test
    public void test_a_patch_without_the_required_fields_is_buffered_once_merged() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(RequestParser.parseRequest(
                        "{\"type\":\"SAVE_SCHEMA\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                                + TestGlobals.COLL + "\",\"schema\":{\"type\":\"object\",\"required\":[\"n\"],"
                                + "\"properties\":{\"status\":{\"type\":\"string\"}}}}"))
                        .getStatus());

        assertEquals(2L, updatedCount(inTransaction(saveWhereNBelow(2, "done"))));
        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        assertEquals("done", committedStatus("c1"));
        assertEquals(1, committedN("c1"));
    }

    @Test
    public void test_the_cap_applies_inside_a_transaction() throws Exception {
        TestUtils.setPrivateField(configuration, "updateMaxMatches", 2);

        final var response = inTransaction(saveWhereNBelow(3, "done"));

        assertEquals(ErrorCode.UPDATE_MATCHES_TOO_MANY.getCode(), response.getErrorCode());
        assertEquals(0, clientTracker.getActiveTransaction(clientId).getPendingOps().size());
    }

    @Test
    public void test_a_hook_rejection_buffers_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "guard",
                "export default (d) => { if (d.n === 2) { throw new Error('keep'); } };"), WRITER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("guard", new LinkedHashSet<>(Set.of(EventType.UPDATED)), "guard",
                        TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, true, WRITER, 1L, 1L,
                        1L, WRITER)));

        final var response = inTransaction(saveWhereNBelow(4, "done"));

        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), response.getErrorCode());
        assertEquals(OperationType.SAVE, response.getType());
        assertEquals(0, clientTracker.getActiveTransaction(clientId).getPendingOps().size());
    }
}
