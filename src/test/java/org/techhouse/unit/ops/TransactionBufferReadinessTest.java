package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionBufferReadinessTest {
    private static final String ABSENT_COLL = "never_created";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private UUID clientId;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        clientId = clientTracker.registerForwardedClient("buffer-readiness");
        processor.processMessage(new StartTransactionRequest(), clientId);
    }

    @AfterEach
    public void tearDown() throws Exception {
        processor.processMessage(new RollbackTransactionRequest(), clientId);
        clientTracker.removeById(clientId);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private OperationResponse bufferSave(String collName) {
        final var request = new SaveRequest(TestGlobals.DB, collName);
        request.setObject(doc("buffered"));
        return processor.processMessage(request, clientId);
    }

    @Test
    public void test_buffer_save_refuses_a_missing_collection_at_buffer_time() {
        final var response = bufferSave(ABSENT_COLL);

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode(),
                "the buffered path answers what the standalone path answers, not a commit-time failure");
    }

    @Test
    public void test_buffer_bulk_save_refuses_a_missing_collection_at_buffer_time() {
        final var request = new BulkSaveRequest(TestGlobals.DB, ABSENT_COLL);
        request.setObjects(List.of(doc("bulk-buffered")));

        final var response = processor.processMessage(request, clientId);

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_buffer_delete_refuses_a_missing_collection_at_buffer_time() {
        final var request = new DeleteRequest(TestGlobals.DB, ABSENT_COLL);
        request.set_id("gone");

        final var response = processor.processMessage(request, clientId);

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_a_readiness_refusal_never_reports_a_half_applied_commit() {
        final var refusal = bufferSave(ABSENT_COLL);

        assertNotEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(), refusal.getErrorCode());
    }

    @Test
    public void test_a_second_op_on_a_live_collection_still_succeeds() {
        assertNotEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), bufferSave(TestGlobals.COLL).getErrorCode());
        final var second = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        second.setObject(doc("buffered-2"));

        final var response = processor.processMessage(second, clientId);

        assertNotEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode(),
                "the lock is already held, so the reordered acquisition is a no-op for later ops");
    }
}
