package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;

public class TransactionFailOnUpdateTest {
    private static final String EXISTS = ErrorCode.DOCUMENT_ALREADY_EXISTS.getCode();
    private static final long WAIT_SECONDS = 10L;
    private static final long BLOCKED_PROBE_MS = 300L;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUp() throws Exception {
        BeforeHookTestSupport.setUpAll();
    }

    @AfterAll
    static void tearDown() throws Exception {
        BeforeHookTestSupport.tearDownAll();
    }

    @BeforeEach
    void reset() throws Exception {
        BeforeHookTestSupport.reset();
    }

    private static SaveRequest request(String id, String name, boolean failOnUpdate) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("name", new JsonString(name));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        request.setFailOnUpdate(failOnUpdate);
        return request;
    }

    private OperationResponse insert(String id, String name, UUID clientId) {
        return processor.processMessage(request(id, name, true), clientId);
    }

    private OperationResponse deleteTxD(UUID clientId) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("tx-d");
        return processor.processMessage(request, clientId);
    }

    private UUID begin() {
        final var client = BeforeHookTestSupport.newClient();
        assertEquals(OperationStatus.OK, processor.processMessage(new StartTransactionRequest(), client).getStatus());
        return client;
    }

    private OperationStatus commit(UUID client) {
        return processor.processMessage(new CommitTransactionRequest(), client).getStatus();
    }

    private String nameOf(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request) instanceof FindByIdResponse found
                ? found.getObject().get("name").asJsonString().getValue()
                : null;
    }

    @Test
    public void test_an_insert_of_a_committed_id_is_refused_and_the_transaction_still_commits() {
        assertEquals(OperationStatus.OK, processor.processMessage(request("tx-c", "committed", false)).getStatus());
        final var client = begin();

        assertEquals(EXISTS, insert("tx-c", "overwrite", client).getErrorCode());
        assertEquals(OperationStatus.OK, insert("tx-other", "kept", client).getStatus());
        assertEquals(OperationStatus.OK, commit(client));

        assertEquals("committed", nameOf("tx-c"));
        assertEquals("kept", nameOf("tx-other"));
    }

    @Test
    public void test_a_second_insert_of_an_id_the_transaction_buffered_is_refused() {
        final var client = begin();

        assertEquals(OperationStatus.OK, insert("tx-own", "first", client).getStatus());
        assertEquals(EXISTS, insert("tx-own", "second", client).getErrorCode());
        assertEquals(OperationStatus.OK, commit(client));

        assertEquals("first", nameOf("tx-own"));
    }

    @Test
    public void test_an_insert_after_the_transaction_deleted_the_id_is_allowed() {
        assertEquals(OperationStatus.OK, processor.processMessage(request("tx-d", "old", false)).getStatus());
        final var client = begin();

        assertEquals(OperationStatus.OK, deleteTxD(client).getStatus());
        assertEquals(OperationStatus.OK, insert("tx-d", "new", client).getStatus());
        assertEquals(OperationStatus.OK, commit(client));

        assertEquals("new", nameOf("tx-d"));
    }

    @Test
    public void test_a_refused_insert_runs_no_before_hook() throws Exception {
        assertEquals(OperationStatus.OK, processor.processMessage(request("tx-h", "stored", false)).getStatus());
        BeforeHookTestSupport.installHook("veto", "veto", "export default (d) => { throw new Error('hooked'); };",
                EventType.CREATED, EventType.UPDATED);
        final var client = begin();

        assertEquals(EXISTS, insert("tx-h", "x", client).getErrorCode());
        processor.processMessage(new RollbackTransactionRequest(), client);
    }

    @Test
    public void test_an_insert_waiting_on_a_transaction_sees_its_commit() throws Exception {
        final var client = begin();
        assertEquals(OperationStatus.OK, insert("tx-wait", "transactional", client).getStatus());

        final var outside = CompletableFuture
                .supplyAsync(() -> processor.processMessage(request("tx-wait", "x", true)));
        assertFalse(waitedOut(outside), "the insert must wait for the transaction's lock");
        assertEquals(OperationStatus.OK, commit(client));

        assertEquals(EXISTS, outside.get(WAIT_SECONDS, TimeUnit.SECONDS).getErrorCode());
        assertEquals("transactional", nameOf("tx-wait"));
    }

    @Test
    public void test_an_insert_waiting_on_a_transaction_succeeds_after_its_rollback() throws Exception {
        final var client = begin();
        assertEquals(OperationStatus.OK, insert("tx-back", "transactional", client).getStatus());

        final var outside = CompletableFuture
                .supplyAsync(() -> processor.processMessage(request("tx-back", "outside", true)));
        assertFalse(waitedOut(outside), "the insert must wait for the transaction's lock");
        processor.processMessage(new RollbackTransactionRequest(), client);

        assertEquals(OperationStatus.OK, outside.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus());
        assertEquals("outside", nameOf("tx-back"));
    }

    private static boolean waitedOut(CompletableFuture<OperationResponse> pending) throws Exception {
        try {
            pending.get(BLOCKED_PROBE_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException stillWaiting) {
            return false;
        }
    }
}
